import { AudioItem, UserPreferences } from '../types';
import { getAudioBlob } from './storage';

class AudioPlayerService {
  private audioElement: HTMLAudioElement | null = null;
  private currentAudioItem: AudioItem | null = null;
  private isPlayingState: boolean = false;
  private onStateChangeListeners: Array<(isPlaying: boolean, currentItem: AudioItem | null, progress: number, duration: number) => void> = [];
  private progressInterval: number | null = null;
  private fadeInInterval: number | null = null;

  constructor() {
    if (typeof window !== 'undefined') {
      this.audioElement = new Audio();
      this.audioElement.addEventListener('ended', () => {
        this.cleanupPlayback();
      });
      this.audioElement.addEventListener('error', (e) => {
        console.warn('Audio playback error:', e);
        this.cleanupPlayback();
      });
    }
  }

  public subscribe(listener: (isPlaying: boolean, currentItem: AudioItem | null, progress: number, duration: number) => void) {
    this.onStateChangeListeners.push(listener);
    return () => {
      this.onStateChangeListeners = this.onStateChangeListeners.filter(l => l !== listener);
    };
  }

  private notify() {
    const progress = this.audioElement ? this.audioElement.currentTime : 0;
    const duration = this.audioElement && !isNaN(this.audioElement.duration) ? this.audioElement.duration : 0;
    for (const l of this.onStateChangeListeners) {
      l(this.isPlayingState, this.currentAudioItem, progress, duration);
    }
  }

  public async play(
    audio: AudioItem,
    preferences: UserPreferences,
    options?: { overrideFadeInSec?: number; onEnded?: () => void }
  ): Promise<void> {
    this.stop();

    this.currentAudioItem = audio;
    this.isPlayingState = true;
    this.notify();

    // Vibrate device if supported and enabled
    if (preferences.vibrateWithAudio && typeof navigator !== 'undefined' && 'vibrate' in navigator) {
      try {
        navigator.vibrate([250, 150, 250]);
      } catch (e) {
        console.log('Vibrate not permitted without direct interaction');
      }
    }

    const masterVol = preferences.masterVolumePercent / 100;
    const itemVol = (audio.volumePercent || 100) / 100;
    const targetVolume = Math.max(0, Math.min(1, masterVol * itemVol));

    // Case 1: Text-To-Speech
    if (audio.audioType === 'TEXT_TO_SPEECH') {
      await this.playTTS(audio.ttsText || audio.label, targetVolume, () => {
        this.cleanupPlayback();
        options?.onEnded?.();
      });
      return;
    }

    // Case 2: Audio File / Recording
    if (!this.audioElement) {
      this.cleanupPlayback();
      return;
    }

    try {
      let src = audio.uriString;

      // Check if stored in IndexedDB blob
      if (src.startsWith('blob_id:')) {
        const id = src.replace('blob_id:', '');
        const blob = await getAudioBlob(id);
        if (blob) {
          src = URL.createObjectURL(blob);
        }
      }

      this.audioElement.src = src;

      // Handle Fade In
      const fadeInEnabled = preferences.fadeInEnabled;
      const fadeInDuration = options?.overrideFadeInSec ?? preferences.fadeInDurationSeconds;

      if (fadeInEnabled && fadeInDuration > 0) {
        this.audioElement.volume = 0;
        await this.audioElement.play();

        const steps = 20;
        const stepTime = (fadeInDuration * 1000) / steps;
        let currentStep = 0;

        if (this.fadeInInterval) clearInterval(this.fadeInInterval);
        this.fadeInInterval = window.setInterval(() => {
          currentStep++;
          if (this.audioElement && this.isPlayingState) {
            const vol = Math.min(targetVolume, (currentStep / steps) * targetVolume);
            this.audioElement.volume = vol;
          }
          if (currentStep >= steps) {
            if (this.fadeInInterval) clearInterval(this.fadeInInterval);
          }
        }, stepTime);
      } else {
        this.audioElement.volume = targetVolume;
        await this.audioElement.play();
      }

      // Track progress
      if (this.progressInterval) clearInterval(this.progressInterval);
      this.progressInterval = window.setInterval(() => {
        this.notify();
      }, 250);

      const handleEnded = () => {
        this.audioElement?.removeEventListener('ended', handleEnded);
        options?.onEnded?.();
      };
      this.audioElement.addEventListener('ended', handleEnded);

    } catch (err) {
      console.error('Failed to play audio:', err);
      this.cleanupPlayback();
    }
  }

  public playTTS(text: string, volume: number = 1.0, onEnded?: () => void): Promise<void> {
    return new Promise((resolve) => {
      if (typeof window === 'undefined' || !('speechSynthesis' in window)) {
        console.warn('Speech synthesis not supported');
        onEnded?.();
        resolve();
        return;
      }

      window.speechSynthesis.cancel();
      const utterance = new SpeechSynthesisUtterance(text);
      utterance.lang = 'pt-BR';
      utterance.volume = Math.max(0, Math.min(1, volume));
      utterance.rate = 1.0;

      // Try selecting a Portuguese voice if available
      const voices = window.speechSynthesis.getVoices();
      const ptVoice = voices.find(v => v.lang.startsWith('pt')) || null;
      if (ptVoice) {
        utterance.voice = ptVoice;
      }

      utterance.onend = () => {
        this.cleanupPlayback();
        onEnded?.();
        resolve();
      };

      utterance.onerror = (e) => {
        console.warn('TTS error:', e);
        this.cleanupPlayback();
        onEnded?.();
        resolve();
      };

      window.speechSynthesis.speak(utterance);
    });
  }

  public stop(): void {
    if (this.fadeInInterval) {
      clearInterval(this.fadeInInterval);
      this.fadeInInterval = null;
    }
    if (this.progressInterval) {
      clearInterval(this.progressInterval);
      this.progressInterval = null;
    }

    if (typeof window !== 'undefined' && 'speechSynthesis' in window) {
      window.speechSynthesis.cancel();
    }

    if (this.audioElement) {
      this.audioElement.pause();
      this.audioElement.currentTime = 0;
    }

    this.cleanupPlayback();
  }

  public pause(): void {
    if (this.audioElement && this.isPlayingState) {
      this.audioElement.pause();
      this.isPlayingState = false;
      this.notify();
    }
  }

  public resume(): void {
    if (this.audioElement && !this.isPlayingState && this.currentAudioItem) {
      this.audioElement.play().then(() => {
        this.isPlayingState = true;
        this.notify();
      }).catch(console.error);
    }
  }

  public seek(seconds: number): void {
    if (this.audioElement && !isNaN(this.audioElement.duration)) {
      this.audioElement.currentTime = Math.max(0, Math.min(this.audioElement.duration, seconds));
      this.notify();
    }
  }

  private cleanupPlayback() {
    this.isPlayingState = false;
    this.currentAudioItem = null;
    if (this.progressInterval) {
      clearInterval(this.progressInterval);
      this.progressInterval = null;
    }
    this.notify();
  }
}

export const audioPlayer = new AudioPlayerService();
