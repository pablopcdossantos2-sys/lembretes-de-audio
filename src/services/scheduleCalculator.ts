import { AudioItem, Profile, RestingPeriod, ScheduledTrigger } from '../types';

export function timeStringToMinutes(timeStr: string): number {
  const [h, m] = timeStr.split(':').map(Number);
  return (h || 0) * 60 + (m || 0);
}

export function minutesToTimeString(minutes: number): string {
  const normalized = ((minutes % 1440) + 1440) % 1440;
  const h = Math.floor(normalized / 60);
  const m = normalized % 60;
  return `${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}`;
}

export function periodCrossesMidnight(startTime: string, endTime: string): boolean {
  const startMin = timeStringToMinutes(startTime);
  const endMin = timeStringToMinutes(endTime);
  return startMin > endMin || startMin === endMin;
}

export function isTimeInRestPeriod(timeStr: string, rest: RestingPeriod): boolean {
  const tMin = timeStringToMinutes(timeStr);
  const sMin = timeStringToMinutes(rest.startTime);
  const eMin = timeStringToMinutes(rest.endTime);

  if (periodCrossesMidnight(rest.startTime, rest.endTime)) {
    // e.g. 22:00 to 07:00: active from 22:00..23:59:59 OR 00:00..06:59:59
    return tMin >= sMin || tMin < eMin;
  } else {
    // Rule: exactly at startTime is in rest. Exactly at endTime is finished resting (allowed to play)
    return tMin >= sMin && tMin < eMin;
  }
}

export function isTimeInAnyRestingPeriod(timeStr: string, restingPeriods: RestingPeriod[]): boolean {
  return restingPeriods.some(rest => isTimeInRestPeriod(timeStr, rest));
}

export function findCoveringRest(timeStr: string, restingPeriods: RestingPeriod[]): RestingPeriod | null {
  return restingPeriods.find(rest => isTimeInRestPeriod(timeStr, rest)) || null;
}

export function mergeRestingPeriods(periods: RestingPeriod[]): RestingPeriod[] {
  if (periods.length === 0) return [];

  const minutesCovered = new Uint8Array(1440);

  for (const p of periods) {
    const sMin = timeStringToMinutes(p.startTime);
    const eMin = timeStringToMinutes(p.endTime);

    if (periodCrossesMidnight(p.startTime, p.endTime)) {
      for (let m = sMin; m < 1440; m++) minutesCovered[m] = 1;
      for (let m = 0; m < eMin; m++) minutesCovered[m] = 1;
    } else {
      for (let m = sMin; m < eMin; m++) minutesCovered[m] = 1;
    }
  }

  // If entire day is covered
  if (minutesCovered.every(val => val === 1)) {
    return [{
      id: 0,
      label: 'Descanso total',
      startTime: '00:00',
      endTime: '00:00'
    }];
  }

  let firstUncovered = -1;
  for (let i = 0; i < 1440; i++) {
    if (minutesCovered[i] === 0) {
      firstUncovered = i;
      break;
    }
  }

  if (firstUncovered === -1) {
    return [{
      id: 0,
      label: 'Descanso total',
      startTime: '00:00',
      endTime: '00:00'
    }];
  }

  const result: RestingPeriod[] = [];
  let i = 0;

  while (i < 1440) {
    const actualMin = (firstUncovered + i) % 1440;
    if (minutesCovered[actualMin] === 1) {
      const intervalStart = actualMin;
      let len = 0;
      while (len < 1440 && minutesCovered[(firstUncovered + i + len) % 1440] === 1) {
        len++;
      }
      const intervalEnd = (intervalStart + len) % 1440;
      const startStr = minutesToTimeString(intervalStart);
      const endStr = minutesToTimeString(intervalEnd);

      const original = periods.find(p => timeStringToMinutes(p.startTime) === intervalStart);
      const label = original ? original.label : 'Descanso';

      result.push({
        id: result.length + 1,
        label,
        startTime: startStr,
        endTime: endStr
      });

      i += len;
    } else {
      i++;
    }
  }

  return result;
}

export function doRestsCoverEntireOperatingHours(profile: Profile): boolean {
  if (!profile.restingPeriods || profile.restingPeriods.length === 0) return false;
  const mergedRests = mergeRestingPeriods(profile.restingPeriods);
  const startMin = timeStringToMinutes(profile.startTime);
  const endMin = timeStringToMinutes(profile.endTime);
  const crosses = startMin > endMin;

  const totalOperatingMinutes = crosses
    ? (1440 - startMin) + endMin
    : endMin - startMin;

  if (totalOperatingMinutes <= 0) return true;

  let uncoveredCount = 0;
  for (let step = 0; step < totalOperatingMinutes; step++) {
    const currentMin = (startMin + step) % 1440;
    const timeStr = minutesToTimeString(currentMin);
    if (!isTimeInAnyRestingPeriod(timeStr, mergedRests)) {
      uncoveredCount++;
    }
  }

  return uncoveredCount === 0;
}

export function isWithinOperatingHours(profile: Profile, timeStr: string): boolean {
  const tMin = timeStringToMinutes(timeStr);
  const sMin = timeStringToMinutes(profile.startTime);
  const eMin = timeStringToMinutes(profile.endTime);
  const crosses = sMin > eMin;

  if (crosses) {
    return tMin >= sMin || tMin <= eMin;
  } else {
    return tMin >= sMin && tMin <= eMin;
  }
}

export function getISODayOfWeek(date: Date): number {
  const day = date.getDay();
  return day === 0 ? 7 : day; // 1=Mon .. 7=Sun
}

export function isProfileActiveAt(profile: Profile, date: Date): boolean {
  if (!profile.isEnabled) return false;

  const dayOfWeek = getISODayOfWeek(date);
  const timeStr = `${date.getHours().toString().padStart(2, '0')}:${date.getMinutes().toString().padStart(2, '0')}`;
  const tMin = timeStringToMinutes(timeStr);
  const sMin = timeStringToMinutes(profile.startTime);
  const eMin = timeStringToMinutes(profile.endTime);
  const crosses = sMin > eMin;

  if (crosses) {
    // 1. Started today on or after startTime
    if (profile.daysOfWeek.includes(dayOfWeek) && tMin >= sMin) {
      return true;
    }
    // 2. Started yesterday and ends today on or before endTime
    const yesterday = dayOfWeek === 1 ? 7 : dayOfWeek - 1;
    if (profile.daysOfWeek.includes(yesterday) && tMin <= eMin) {
      return true;
    }
    return false;
  } else {
    return profile.daysOfWeek.includes(dayOfWeek) && isWithinOperatingHours(profile, timeStr);
  }
}

export function findActiveProfileFor(date: Date, profiles: Profile[]): Profile | null {
  const enabled = profiles.filter(p => p.isEnabled);
  if (enabled.length === 0) return null;

  const candidates = enabled.filter(p => isProfileActiveAt(p, date));
  if (candidates.length === 0) return null;

  // Highest priority, then highest ID
  return candidates.sort((a, b) => {
    if (b.priority !== a.priority) return b.priority - a.priority;
    return b.id - a.id;
  })[0];
}

export function selectAudioForProfile(
  profile: Profile,
  activeAudios: AudioItem[],
  seqIndex: number
): { audioId: number | null; audioLabel: string; isRandom: boolean } {
  const eligible = profile.audioIds.length > 0
    ? activeAudios.filter(a => profile.audioIds.includes(a.id))
    : activeAudios;

  if (eligible.length === 0) {
    return { audioId: null, audioLabel: 'Nenhum áudio disponível', isRandom: false };
  }

  switch (profile.playbackOrder) {
    case 'SPECIFIC': {
      const specific = eligible.find(a => a.id === profile.specificAudioId) || eligible[0];
      return { audioId: specific.id, audioLabel: specific.label, isRandom: false };
    }
    case 'RANDOM': {
      return { audioId: null, audioLabel: 'Áudio aleatório', isRandom: true };
    }
    case 'SEQUENTIAL':
    default: {
      const index = ((seqIndex % eligible.length) + eligible.length) % eligible.length;
      const item = eligible[index];
      return { audioId: item.id, audioLabel: item.label, isRandom: false };
    }
  }
}

export function pickWeightedRandomAudio(audios: AudioItem[]): AudioItem | null {
  const active = audios.filter(a => a.isActive && !a.isMissing);
  if (active.length === 0) return null;

  const totalWeight = active.reduce((sum, a) => sum + Math.max(1, Math.min(5, a.weight || 3)), 0);
  const randomVal = Math.floor(Math.random() * totalWeight) + 1;
  let accumulated = 0;

  for (const audio of active) {
    accumulated += Math.max(1, Math.min(5, audio.weight || 3));
    if (randomVal <= accumulated) {
      return audio;
    }
  }
  return active[0];
}

export function getNextNTriggers(
  now: Date,
  count: number,
  profiles: Profile[],
  activeAudios: AudioItem[],
  snoozeUntil: Date | null = null,
  lastSequentialIndex: number = 0
): ScheduledTrigger[] {
  const enabledProfiles = profiles.filter(p => p.isEnabled);
  if (enabledProfiles.length === 0 || activeAudios.length === 0) {
    return [];
  }

  const triggers: ScheduledTrigger[] = [];
  const effectiveStart = snoozeUntil && snoozeUntil.getTime() > now.getTime() ? snoozeUntil : now;

  let currentSeqIndex = lastSequentialIndex;
  const maxDays = 14;

  const baseYear = now.getFullYear();
  const baseMonth = now.getMonth();
  const baseDate = now.getDate();

  for (let dayOffset = 0; dayOffset < maxDays && triggers.length < count * 2; dayOffset++) {
    const curDate = new Date(baseYear, baseMonth, baseDate + dayOffset);
    const dayOfWeek = getISODayOfWeek(curDate);

    // Sort profiles by priority descending, then id descending
    const sortedProfiles = [...enabledProfiles].sort((a, b) => {
      if (b.priority !== a.priority) return b.priority - a.priority;
      return b.id - a.id;
    });

    for (const profile of sortedProfiles) {
      if (!profile.daysOfWeek.includes(dayOfWeek)) {
        continue;
      }

      const mergedRests = mergeRestingPeriods(profile.restingPeriods);
      const [startH, startM] = profile.startTime.split(':').map(Number);
      const [endH, endM] = profile.endTime.split(':').map(Number);

      const startInstant = new Date(curDate.getFullYear(), curDate.getMonth(), curDate.getDate(), startH, startM, 0, 0);
      const endInstant = new Date(curDate.getFullYear(), curDate.getMonth(), curDate.getDate(), endH, endM, 0, 0);

      const crosses = timeStringToMinutes(profile.startTime) > timeStringToMinutes(profile.endTime);
      if (crosses) {
        endInstant.setDate(endInstant.getDate() + 1);
      }

      const stepMinutes = Math.max(1, profile.intervalMinutes);
      let currentInstant = new Date(startInstant.getTime());

      while (currentInstant.getTime() <= endInstant.getTime() && triggers.length < count * 2) {
        if (currentInstant.getTime() > effectiveStart.getTime()) {
          const timeStr = `${currentInstant.getHours().toString().padStart(2, '0')}:${currentInstant.getMinutes().toString().padStart(2, '0')}`;
          const coveringRest = findCoveringRest(timeStr, mergedRests);

          if (!coveringRest) {
            const { audioId, audioLabel, isRandom } = selectAudioForProfile(
              profile,
              activeAudios,
              currentSeqIndex
            );
            currentSeqIndex++;

            triggers.push({
              triggerTime: currentInstant.toISOString(),
              profileId: profile.id,
              profileName: profile.name,
              predictedAudioId: audioId,
              predictedAudioLabel: audioLabel,
              isRandomAudio: isRandom
            });
          } else if (profile.resumePolicy === 'AT_REST_END') {
            const [restEndH, restEndM] = coveringRest.endTime.split(':').map(Number);
            const restEndInstant = new Date(currentInstant.getFullYear(), currentInstant.getMonth(), currentInstant.getDate(), restEndH, restEndM, 0, 0);

            if (periodCrossesMidnight(coveringRest.startTime, coveringRest.endTime)) {
              if (timeStringToMinutes(timeStr) >= timeStringToMinutes(coveringRest.startTime)) {
                restEndInstant.setDate(restEndInstant.getDate() + 1);
              }
            }

            const restEndTimeStr = `${restEndInstant.getHours().toString().padStart(2, '0')}:${restEndInstant.getMinutes().toString().padStart(2, '0')}`;

            if (
              restEndInstant.getTime() > effectiveStart.getTime() &&
              restEndInstant.getTime() <= endInstant.getTime() &&
              !isTimeInAnyRestingPeriod(restEndTimeStr, mergedRests) &&
              !triggers.some(t => new Date(t.triggerTime).getTime() === restEndInstant.getTime())
            ) {
              const { audioId, audioLabel, isRandom } = selectAudioForProfile(
                profile,
                activeAudios,
                currentSeqIndex
              );
              currentSeqIndex++;

              triggers.push({
                triggerTime: restEndInstant.toISOString(),
                profileId: profile.id,
                profileName: profile.name,
                predictedAudioId: audioId,
                predictedAudioLabel: audioLabel,
                isRandomAudio: isRandom
              });
            }
          }
        }

        currentInstant = new Date(currentInstant.getTime() + stepMinutes * 60 * 1000);
      }
    }
  }

  // Sort strictly chronologically and take count
  return triggers
    .sort((a, b) => new Date(a.triggerTime).getTime() - new Date(b.triggerTime).getTime())
    .slice(0, count);
}

export function checkDayConflicts(profiles: Profile[]): string | null {
  const enabled = profiles.filter(p => p.isEnabled);
  if (enabled.length < 2) return null;

  const dayNames = ['Segunda', 'Terça', 'Quarta', 'Quinta', 'Sexta', 'Sábado', 'Domingo'];
  const dayConflicts: string[] = [];

  for (let day = 1; day <= 7; day++) {
    const overlapping = enabled.filter(p => p.daysOfWeek.includes(day));
    if (overlapping.length > 1) {
      // Check priority difference
      const priorities = overlapping.map(p => p.priority);
      const allSame = priorities.every(p => p === priorities[0]);
      if (allSame) {
        dayConflicts.push(dayNames[day - 1]);
      }
    }
  }

  if (dayConflicts.length > 0) {
    return `Atenção: Mais de um perfil ativo compartilha os mesmos dias com a mesma prioridade (${dayConflicts.join(', ')}). O de maior ID terá precedência.`;
  }
  return null;
}
