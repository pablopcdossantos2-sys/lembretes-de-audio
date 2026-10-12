import { AudioItem, Profile, ReminderEntity, UserPreferences, BackupData } from '../types';

const STORAGE_KEYS = {
  PREFERENCES: 'lembretes_audio_preferences',
  PROFILES: 'lembretes_audio_profiles',
  AUDIOS: 'lembretes_audio_audios',
  REMINDERS: 'lembretes_audio_reminders',
};

const DEFAULT_PREFERENCES: UserPreferences = {
  isAppMasterEnabled: false,
  masterVolumePercent: 100,
  useAppVolumeOnly: true,
  fadeInEnabled: true,
  fadeInDurationSeconds: 2.0,
  vibrateWithAudio: true,
  showLabelInNotification: true,
  respectDoNotDisturb: true,
  skipIfCallInProgress: true,
  skipIfBluetoothConnected: true,
  defaultResumePolicy: 'NEXT_GRID_MARK',
  snoozeUntilEpochMillis: 0,
  lastSequentialIndex: 0,
  themeMode: 'system'
};

const DEFAULT_PROFILES: Profile[] = [
  {
    id: 1,
    name: 'Horário Comercial',
    isEnabled: false, // Starts disabled until user adds audio
    priority: 1,
    daysOfWeek: [1, 2, 3, 4, 5], // Seg-Sex
    startTime: '08:00',
    endTime: '18:00',
    intervalMinutes: 15,
    playbackOrder: 'SEQUENTIAL',
    specificAudioId: null,
    audioIds: [],
    restingPeriods: [
      {
        id: 1,
        label: 'Almoço',
        startTime: '12:00',
        endTime: '13:00'
      }
    ],
    resumePolicy: 'NEXT_GRID_MARK',
    fadeInOverrideEnabled: false,
    fadeInDurationSeconds: 2.0
  },
  {
    id: 2,
    name: 'Final de Semana',
    isEnabled: false,
    priority: 0,
    daysOfWeek: [6, 7], // Sab-Dom
    startTime: '10:00',
    endTime: '20:00',
    intervalMinutes: 30,
    playbackOrder: 'RANDOM',
    specificAudioId: null,
    audioIds: [],
    restingPeriods: [
      {
        id: 2,
        label: 'Descanso da Tarde',
        startTime: '13:00',
        endTime: '15:00'
      }
    ],
    resumePolicy: 'AT_REST_END',
    fadeInOverrideEnabled: true,
    fadeInDurationSeconds: 3.0
  }
];

// As required by Section 2.1:
// "O app não vem com nenhum áudio embutido. Por padrão, a biblioteca começa vazia
// e é o usuário quem insere todos os áudios necessários."
const DEFAULT_AUDIOS: AudioItem[] = [];

const DEFAULT_REMINDERS: ReminderEntity[] = [];

// IndexedDB Helper for audio storage
const DB_NAME = 'LembretesAudioDB';
const DB_VERSION = 1;
const AUDIO_STORE = 'audioBlobs';

function openIndexedDB(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined' || !window.indexedDB) {
      reject(new Error('IndexedDB not supported'));
      return;
    }
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(AUDIO_STORE)) {
        db.createObjectStore(AUDIO_STORE);
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
}

export async function saveAudioBlob(id: number | string, blob: Blob): Promise<void> {
  try {
    const db = await openIndexedDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(AUDIO_STORE, 'readwrite');
      const store = tx.objectStore(AUDIO_STORE);
      const req = store.put(blob, String(id));
      req.onsuccess = () => resolve();
      req.onerror = () => reject(req.error);
    });
  } catch (err) {
    console.warn('Failed to save audio blob to IndexedDB:', err);
  }
}

export async function getAudioBlob(id: number | string): Promise<Blob | null> {
  try {
    const db = await openIndexedDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(AUDIO_STORE, 'readonly');
      const store = tx.objectStore(AUDIO_STORE);
      const req = store.get(String(id));
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  } catch (err) {
    console.warn('Failed to get audio blob from IndexedDB:', err);
    return null;
  }
}

export async function hasAudioBlob(id: number | string): Promise<boolean> {
  const blob = await getAudioBlob(id);
  return blob !== null;
}

export async function deleteAudioBlob(id: number | string): Promise<void> {
  try {
    const db = await openIndexedDB();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(AUDIO_STORE, 'readwrite');
      const store = tx.objectStore(AUDIO_STORE);
      const req = store.delete(String(id));
      req.onsuccess = () => resolve();
      req.onerror = () => reject(req.error);
    });
  } catch (err) {
    console.warn('Failed to delete audio blob from IndexedDB:', err);
  }
}

// LocalStorage helpers for models
export function loadPreferences(): UserPreferences {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.PREFERENCES);
    if (raw) {
      return { ...DEFAULT_PREFERENCES, ...JSON.parse(raw) };
    }
  } catch (e) {
    console.error('Error reading preferences:', e);
  }
  return DEFAULT_PREFERENCES;
}

export function savePreferences(prefs: UserPreferences): void {
  try {
    localStorage.setItem(STORAGE_KEYS.PREFERENCES, JSON.stringify(prefs));
  } catch (e) {
    console.error('Error saving preferences:', e);
  }
}

export function loadProfiles(): Profile[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.PROFILES);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed) && parsed.length > 0) {
        return parsed;
      }
    }
  } catch (e) {
    console.error('Error reading profiles:', e);
  }
  return DEFAULT_PROFILES;
}

export function saveProfiles(profiles: Profile[]): void {
  try {
    localStorage.setItem(STORAGE_KEYS.PROFILES, JSON.stringify(profiles));
  } catch (e) {
    console.error('Error saving profiles:', e);
  }
}

export function loadAudios(): AudioItem[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.AUDIOS);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return parsed;
      }
    }
  } catch (e) {
    console.error('Error reading audios:', e);
  }
  return DEFAULT_AUDIOS;
}

export function saveAudios(audios: AudioItem[]): void {
  try {
    localStorage.setItem(STORAGE_KEYS.AUDIOS, JSON.stringify(audios));
  } catch (e) {
    console.error('Error saving audios:', e);
  }
}

export function loadReminders(): ReminderEntity[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.REMINDERS);
    if (raw) {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) {
        return parsed;
      }
    }
  } catch (e) {
    console.error('Error reading reminders:', e);
  }
  return DEFAULT_REMINDERS;
}

export function saveReminders(reminders: ReminderEntity[]): void {
  try {
    localStorage.setItem(STORAGE_KEYS.REMINDERS, JSON.stringify(reminders));
  } catch (e) {
    console.error('Error saving reminders:', e);
  }
}

export function exportBackupData(): BackupData {
  return {
    version: 1,
    timestamp: Date.now(),
    profiles: loadProfiles(),
    audios: loadAudios().map(a => ({
      ...a,
      // Audio blobs themselves are not exported per Section 2.8:
      // "Os arquivos de áudio em si não são copiados (apenas as referências);
      // ao importar, sinalizar áudios não encontrados para o usuário reassociar."
    })),
    reminders: loadReminders(),
    preferences: loadPreferences(),
  };
}

export async function importBackupDataAsync(data: BackupData): Promise<{ success: boolean; message: string; missingCount: number }> {
  try {
    if (!data || typeof data !== 'object') {
      return { success: false, message: 'Arquivo de backup inválido ou corrompido.', missingCount: 0 };
    }

    let missingCount = 0;
    let audiosToSave: AudioItem[] = [];

    if (data.audios && Array.isArray(data.audios)) {
      audiosToSave = await Promise.all(data.audios.map(async (item) => {
        if (item.audioType === 'AUDIO_FILE' || item.audioType === 'RECORDING') {
          if (item.uriString?.startsWith('blob_id:')) {
            const id = item.uriString.replace('blob_id:', '');
            const exists = await hasAudioBlob(id);
            if (!exists) {
              missingCount++;
              return { ...item, isMissing: true };
            }
          } else if (!item.uriString) {
            missingCount++;
            return { ...item, isMissing: true };
          }
        }
        return { ...item, isMissing: false };
      }));
      saveAudios(audiosToSave);
    }

    if (data.profiles && Array.isArray(data.profiles)) {
      saveProfiles(data.profiles);
    }
    if (data.reminders && Array.isArray(data.reminders)) {
      saveReminders(data.reminders);
    }
    if (data.preferences) {
      savePreferences(data.preferences);
    }

    const message = missingCount > 0
      ? `Backup importado! Atenção: ${missingCount} áudio(s) precisam ser reassociados pois arquivos binários não são transferidos no backup.`
      : 'Dados importados com sucesso!';

    return { success: true, message, missingCount };
  } catch (err: any) {
    return { success: false, message: `Erro ao importar: ${err?.message || 'Arquivo inválido'}`, missingCount: 0 };
  }
}

export function resetAllData(): void {
  localStorage.removeItem(STORAGE_KEYS.PREFERENCES);
  localStorage.removeItem(STORAGE_KEYS.PROFILES);
  localStorage.removeItem(STORAGE_KEYS.AUDIOS);
  localStorage.removeItem(STORAGE_KEYS.REMINDERS);
}
