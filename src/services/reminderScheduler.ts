import { AudioItem, Profile, ScheduledTrigger, UserPreferences } from '../types';
import {
  findActiveProfileFor,
  findCoveringRest,
  isTimeInAnyRestingPeriod,
  mergeRestingPeriods,
  pickWeightedRandomAudio,
  selectAudioForProfile,
  timeStringToMinutes
} from './scheduleCalculator';
import { audioPlayer } from './audioPlayer';

type TriggerCallback = (trigger: {
  profile: Profile;
  audio: AudioItem;
  timestamp: Date;
}) => void;

type SkipCallback = (reason: string, timeStr: string) => void;

class ReminderSchedulerService {
  private timerId: number | null = null;
  private onTriggerCallback: TriggerCallback | null = null;
  private onSkipCallback: SkipCallback | null = null;
  private lastTriggeredMinute: string = '';
  private lastCheckedTimestamp: number = Date.now();

  public setTriggerCallback(callback: TriggerCallback) {
    this.onTriggerCallback = callback;
  }

  public setSkipCallback(callback: SkipCallback) {
    this.onSkipCallback = callback;
  }

  public start(
    getProfiles: () => Profile[],
    getAudios: () => AudioItem[],
    getPreferences: () => UserPreferences,
    onSequentialAdvance: () => void
  ) {
    if (this.timerId) {
      clearInterval(this.timerId);
    }

    this.lastCheckedTimestamp = Date.now();

    // Check every 3 seconds for precise time matching and missed trigger detection
    this.timerId = window.setInterval(() => {
      this.checkTriggers(getProfiles, getAudios, getPreferences, onSequentialAdvance);
    }, 3000);
  }

  public stop() {
    if (this.timerId) {
      clearInterval(this.timerId);
      this.timerId = null;
    }
  }

  public async isBluetoothOrHeadsetConnected(): Promise<boolean> {
    try {
      if (typeof navigator !== 'undefined' && navigator.mediaDevices?.enumerateDevices) {
        const devices = await navigator.mediaDevices.enumerateDevices();
        const hasBluetooth = devices.some(d => {
          const lbl = (d.label || '').toLowerCase();
          return lbl.includes('bluetooth') || lbl.includes('headset') || lbl.includes('airpods') || lbl.includes('fone');
        });
        return hasBluetooth;
      }
    } catch (_) {}
    return false;
  }

  private async checkTriggers(
    getProfiles: () => Profile[],
    getAudios: () => AudioItem[],
    getPreferences: () => UserPreferences,
    onSequentialAdvance: () => void
  ) {
    const prefs = getPreferences();
    if (!prefs.isAppMasterEnabled) {
      this.lastCheckedTimestamp = Date.now();
      return;
    }

    const now = new Date();
    const currentEpoch = now.getTime();
    const profiles = getProfiles();
    const activeAudios = getAudios().filter(a => a.isActive && !a.isMissing);

    // Section 2.3: Check for missed triggers when tab/device was asleep or frozen (> 65 seconds gap)
    const elapsedSinceLastCheck = currentEpoch - this.lastCheckedTimestamp;
    if (elapsedSinceLastCheck > 65000 && activeAudios.length > 0) {
      const activeProfile = findActiveProfileFor(new Date(this.lastCheckedTimestamp + 60000), profiles);
      if (activeProfile) {
        const missedTime = new Date(this.lastCheckedTimestamp + 60000);
        const timeFormatted = missedTime.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        this.dispatchNotification(
          'Lembrete Perdido',
          `Lembrete perdido às ${timeFormatted}. Disparo descartado.`,
          'default'
        );
        this.onSkipCallback?.(`Lembrete perdido às ${timeFormatted}`, timeFormatted);
      }
    }
    this.lastCheckedTimestamp = currentEpoch;

    // Check snooze
    if (prefs.snoozeUntilEpochMillis > currentEpoch) {
      return;
    }

    const currentMinuteKey = `${now.getFullYear()}-${now.getMonth()}-${now.getDate()}_${now.getHours()}:${now.getMinutes()}`;
    if (this.lastTriggeredMinute === currentMinuteKey) {
      return; // Already triggered this minute
    }

    if (activeAudios.length === 0) return;

    const activeProfile = findActiveProfileFor(now, profiles);
    if (!activeProfile) return;

    const mergedRests = mergeRestingPeriods(activeProfile.restingPeriods);
    const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;

    if (isTimeInAnyRestingPeriod(timeStr, mergedRests)) {
      return;
    }

    // Check if the current minute aligns with the profile interval from startTime
    const [startH, startM] = activeProfile.startTime.split(':').map(Number);
    const startMin = startH * 60 + startM;
    const currentMin = now.getHours() * 60 + now.getMinutes();

    let diff = currentMin - startMin;
    if (diff < 0) diff += 1440;

    const interval = Math.max(1, activeProfile.intervalMinutes);
    if (diff % interval === 0) {
      this.lastTriggeredMinute = currentMinuteKey;

      // Section 2.3: Check Bluetooth skip condition
      if (prefs.skipIfBluetoothConnected) {
        const isBluetooth = await this.isBluetoothOrHeadsetConnected();
        if (isBluetooth) {
          const reason = 'Lembrete ignorado: fone Bluetooth conectado';
          this.dispatchNotification(activeProfile.name, reason, 'default');
          this.onSkipCallback?.(reason, timeStr);
          return;
        }
      }

      this.triggerAudio(activeProfile, activeAudios, prefs, onSequentialAdvance);
    }
  }

  public triggerAudio(
    profile: Profile,
    audios: AudioItem[],
    preferences: UserPreferences,
    onSequentialAdvance: () => void,
    forceAudio?: AudioItem
  ) {
    let chosenAudio: AudioItem | null = forceAudio || null;

    if (!chosenAudio) {
      const eligible = profile.audioIds.length > 0
        ? audios.filter(a => profile.audioIds.includes(a.id))
        : audios;

      if (eligible.length === 0) return;

      if (profile.playbackOrder === 'RANDOM') {
        chosenAudio = pickWeightedRandomAudio(eligible) || eligible[0];
      } else if (profile.playbackOrder === 'SPECIFIC') {
        chosenAudio = eligible.find(a => a.id === profile.specificAudioId) || eligible[0];
      } else {
        // SEQUENTIAL
        const seqIndex = preferences.lastSequentialIndex || 0;
        const index = ((seqIndex % eligible.length) + eligible.length) % eligible.length;
        chosenAudio = eligible[index];
        onSequentialAdvance();
      }
    }

    if (!chosenAudio) return;

    // Show system notification with separate channel/tag
    const notifBody = preferences.showLabelInNotification ? chosenAudio.label : 'Lembrete em reprodução';
    this.dispatchNotification(profile.name, notifBody, 'playback');

    // Play sound with fade-in configuration
    const overrideFade = profile.fadeInOverrideEnabled ? profile.fadeInDurationSeconds : undefined;
    audioPlayer.play(chosenAudio, preferences, { overrideFadeInSec: overrideFade });

    if (this.onTriggerCallback) {
      this.onTriggerCallback({
        profile,
        audio: chosenAudio,
        timestamp: new Date()
      });
    }
  }

  public dispatchNotification(title: string, body: string, tag: 'service' | 'playback' | 'default' = 'default') {
    if (typeof window !== 'undefined' && 'Notification' in window && Notification.permission === 'granted') {
      try {
        new Notification(title, {
          body,
          tag,
          icon: '/favicon.svg'
        });
      } catch (e) {
        console.log('Notification dispatch err:', e);
      }
    }
  }

  public getCurrentStatusText(
    preferences: UserPreferences,
    profiles: Profile[],
    nextTriggerTime?: string
  ): string {
    if (!preferences.isAppMasterEnabled) {
      return 'Lembretes desativados';
    }

    const now = new Date();
    if (preferences.snoozeUntilEpochMillis > now.getTime()) {
      const sDate = new Date(preferences.snoozeUntilEpochMillis);
      const timeStr = sDate.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
      return `Pausado até ${timeStr}`;
    }

    const activeProfile = findActiveProfileFor(now, profiles);
    if (!activeProfile) {
      return 'Nenhum perfil ativo no momento';
    }

    const mergedRests = mergeRestingPeriods(activeProfile.restingPeriods);
    const nowTimeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;
    const coveringRest = findCoveringRest(nowTimeStr, mergedRests);

    if (coveringRest) {
      return `Em descanso até ${coveringRest.endTime}`;
    }

    if (nextTriggerTime) {
      const nDate = new Date(nextTriggerTime);
      return `Próximo áudio às ${nDate.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}`;
    }

    return `Ativo: ${activeProfile.name}`;
  }
}

export const reminderScheduler = new ReminderSchedulerService();
