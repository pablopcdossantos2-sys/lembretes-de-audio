export type PlaybackOrder = 'SEQUENTIAL' | 'RANDOM' | 'SPECIFIC';

export type ResumePolicy = 'NEXT_GRID_MARK' | 'AT_REST_END';

export type AudioType = 'AUDIO_FILE' | 'RECORDING' | 'TEXT_TO_SPEECH';

export interface RestingPeriod {
  id: number;
  label: string;
  startTime: string; // "HH:mm" e.g. "12:00"
  endTime: string;   // "HH:mm" e.g. "13:00"
}

export interface AudioItem {
  id: number;
  uriString: string; // Data URL, blob URL or identifier
  label: string;
  weight: number; // 1..5
  volumePercent: number; // 0..100
  isActive: boolean;
  isMissing?: boolean;
  audioType: AudioType;
  ttsText?: string | null;
  dateAdded: number; // timestamp
}

export interface Profile {
  id: number;
  name: string;
  isEnabled: boolean;
  priority: number;
  daysOfWeek: number[]; // 1 = Monday ... 7 = Sunday
  startTime: string; // "08:00"
  endTime: string;   // "22:00"
  intervalMinutes: number; // e.g. 15
  playbackOrder: PlaybackOrder;
  specificAudioId?: number | null;
  audioIds: number[]; // Empty array means all active audios
  restingPeriods: RestingPeriod[];
  resumePolicy: ResumePolicy;
  fadeInOverrideEnabled: boolean;
  fadeInDurationSeconds: number;
}

export interface ScheduledTrigger {
  triggerTime: string; // ISO string
  profileId: number;
  profileName: string;
  predictedAudioId: number | null;
  predictedAudioLabel: string;
  isRandomAudio: boolean;
}

export interface UserPreferences {
  isAppMasterEnabled: boolean;
  masterVolumePercent: number;
  useAppVolumeOnly: boolean;
  fadeInEnabled: boolean;
  fadeInDurationSeconds: number;
  vibrateWithAudio: boolean;
  showLabelInNotification: boolean;
  respectDoNotDisturb: boolean;
  skipIfCallInProgress: boolean;
  skipIfBluetoothConnected: boolean;
  defaultResumePolicy: ResumePolicy;
  snoozeUntilEpochMillis: number;
  lastSequentialIndex: number;
  themeMode: 'system' | 'dark' | 'light';
}

export interface ReminderEntity {
  id: number;
  title: string;
  text?: string | null;
  audioUri?: string | null;
  periodicityMinutes: number;
  daysOfWeek: number[]; // 1..7
  startHour: number;
  startMinute: number;
  endHour: number;
  endMinute: number;
  isEnabled: boolean;
  audioType: AudioType;
  volumePercent: number;
  createdAt: number;
}

export type NavigationScreen = 'HOME' | 'PROFILES' | 'LIBRARY' | 'SETTINGS' | 'RECORD';

export interface BackupData {
  version: number;
  timestamp: number;
  profiles: Profile[];
  audios: AudioItem[];
  reminders: ReminderEntity[];
  preferences: UserPreferences;
}
