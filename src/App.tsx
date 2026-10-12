import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Home,
  Clock,
  Library,
  Settings as SettingsIcon,
  Mic,
  Volume2,
  CheckCircle2,
  Bell
} from 'lucide-react';
import { AudioItem, NavigationScreen, Profile, ReminderEntity, UserPreferences } from './types';
import {
  loadAudios,
  loadPreferences,
  loadProfiles,
  loadReminders,
  saveAudios,
  savePreferences,
  saveProfiles,
  saveReminders
} from './services/storage';
import { reminderScheduler } from './services/reminderScheduler';
import { HomeScreen } from './screens/HomeScreen';
import { ProfilesScreen } from './screens/ProfilesScreen';
import { LibraryScreen } from './screens/LibraryScreen';
import { RecordReminderScreen } from './screens/RecordReminderScreen';
import { SettingsScreen } from './screens/SettingsScreen';
import { AudioPlayerBar } from './components/AudioPlayerBar';

export const App: React.FC = () => {
  const [currentScreen, setCurrentScreen] = useState<NavigationScreen>('HOME');

  // Application Data States
  const [preferences, setPreferences] = useState<UserPreferences>(() => loadPreferences());
  const [profiles, setProfiles] = useState<Profile[]>(() => loadProfiles());
  const [audios, setAudios] = useState<AudioItem[]>(() => loadAudios());
  const [reminders, setReminders] = useState<ReminderEntity[]>(() => loadReminders());

  // Toast State
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const toastTimeoutRef = useRef<number | null>(null);

  const showToast = useCallback((msg: string) => {
    setToastMessage(msg);
    if (toastTimeoutRef.current) clearTimeout(toastTimeoutRef.current);
    toastTimeoutRef.current = window.setTimeout(() => {
      setToastMessage(null);
    }, 3800);
  }, []);

  // Sync state changes to storage
  const handleUpdatePreferences = useCallback((updates: Partial<UserPreferences>) => {
    setPreferences((prev) => {
      const next = { ...prev, ...updates };
      savePreferences(next);
      return next;
    });
  }, []);

  const handleSaveProfile = useCallback((profile: Profile) => {
    setProfiles((prev) => {
      const existing = prev.findIndex((p) => p.id === profile.id);
      let updated: Profile[];
      if (existing >= 0) {
        updated = [...prev];
        updated[existing] = profile;
      } else {
        updated = [profile, ...prev];
      }
      saveProfiles(updated);
      return updated;
    });
  }, []);

  const handleDeleteProfile = useCallback((id: number) => {
    setProfiles((prev) => {
      const updated = prev.filter((p) => p.id !== id);
      saveProfiles(updated);
      return updated;
    });
  }, []);

  const handleToggleProfile = useCallback((id: number, enabled: boolean) => {
    setProfiles((prev) => {
      const updated = prev.map((p) => (p.id === id ? { ...p, isEnabled: enabled } : p));
      saveProfiles(updated);
      return updated;
    });
  }, []);

  const handleAddAudios = useCallback((newAudios: AudioItem[]) => {
    setAudios((prev) => {
      const updated = [...newAudios, ...prev];
      saveAudios(updated);
      return updated;
    });
  }, []);

  const handleUpdateAudio = useCallback((audio: AudioItem) => {
    setAudios((prev) => {
      const updated = prev.map((a) => (a.id === audio.id ? audio : a));
      saveAudios(updated);

      // If no active audios left, disable master per Section 2.1
      const activeCount = updated.filter(a => a.isActive && !a.isMissing).length;
      if (activeCount === 0 && preferences.isAppMasterEnabled) {
        handleUpdatePreferences({ isAppMasterEnabled: false });
        showToast('Nenhum áudio ativo restante na biblioteca. Lembretes desativados automaticamente.');
      }

      return updated;
    });
  }, [preferences.isAppMasterEnabled, handleUpdatePreferences, showToast]);

  const handleDeleteAudio = useCallback((id: number) => {
    setAudios((prev) => {
      const updated = prev.filter((a) => a.id !== id);
      saveAudios(updated);

      // If no active audios left, disable master per Section 2.1
      const activeCount = updated.filter(a => a.isActive && !a.isMissing).length;
      if (activeCount === 0 && preferences.isAppMasterEnabled) {
        handleUpdatePreferences({ isAppMasterEnabled: false });
        showToast('Biblioteca sem áudios ativos. Lembretes desativados automaticamente.');
      }

      return updated;
    });
  }, [preferences.isAppMasterEnabled, handleUpdatePreferences, showToast]);

  const handleSaveReminder = useCallback((reminder: ReminderEntity) => {
    setReminders((prev) => {
      const existing = prev.findIndex((r) => r.id === reminder.id);
      let updated: ReminderEntity[];
      if (existing >= 0) {
        updated = [...prev];
        updated[existing] = reminder;
      } else {
        updated = [reminder, ...prev];
      }
      saveReminders(updated);
      return updated;
    });
  }, []);

  const handleDeleteReminder = useCallback((id: number) => {
    setReminders((prev) => {
      const updated = prev.filter((r) => r.id !== id);
      saveReminders(updated);
      return updated;
    });
  }, []);

  const reloadAllData = useCallback(() => {
    setPreferences(loadPreferences());
    setProfiles(loadProfiles());
    setAudios(loadAudios());
    setReminders(loadReminders());
  }, []);

  // References for scheduler loop to access latest state
  const profilesRef = useRef(profiles);
  const audiosRef = useRef(audios);
  const preferencesRef = useRef(preferences);

  useEffect(() => {
    profilesRef.current = profiles;
  }, [profiles]);

  useEffect(() => {
    audiosRef.current = audios;
  }, [audios]);

  useEffect(() => {
    preferencesRef.current = preferences;
  }, [preferences]);

  // Start background scheduler service
  useEffect(() => {
    reminderScheduler.setTriggerCallback((trigger) => {
      showToast(`Disparando: "${trigger.audio.label}" (${trigger.profile.name})`);
    });

    reminderScheduler.setSkipCallback((reason) => {
      showToast(reason);
    });

    reminderScheduler.start(
      () => profilesRef.current,
      () => audiosRef.current,
      () => preferencesRef.current,
      () => {
        handleUpdatePreferences({
          lastSequentialIndex: (preferencesRef.current.lastSequentialIndex || 0) + 1
        });
      }
    );

    return () => {
      reminderScheduler.stop();
    };
  }, [handleUpdatePreferences, showToast]);

  const navItems = [
    { screen: 'HOME' as NavigationScreen, label: 'Início', icon: Home },
    { screen: 'PROFILES' as NavigationScreen, label: 'Perfis', icon: Clock },
    { screen: 'LIBRARY' as NavigationScreen, label: 'Biblioteca', icon: Library },
    { screen: 'SETTINGS' as NavigationScreen, label: 'Config', icon: SettingsIcon },
  ];

  const getScreenTitle = () => {
    switch (currentScreen) {
      case 'HOME':
        return 'Início';
      case 'PROFILES':
        return 'Perfis de Funcionamento';
      case 'LIBRARY':
        return 'Biblioteca de Áudios';
      case 'SETTINGS':
        return 'Configurações';
      case 'RECORD':
        return 'Gravar Lembrete';
    }
  };

  return (
    <div className="flex min-h-screen bg-slate-950 text-slate-100">
      {/* Side Navigation Rail for Large Screens (Desktop / Tablet) */}
      <aside className="hidden md:flex flex-col w-64 bg-slate-900 border-r border-slate-800 p-4 flex-shrink-0">
        <div className="flex items-center gap-3 px-3 py-3 mb-6">
          <div className="p-2.5 rounded-2xl bg-blue-600/30 text-blue-400 border border-blue-500/30 shadow-md">
            <Volume2 className="w-6 h-6" />
          </div>
          <div>
            <h1 className="font-bold text-base text-slate-100 leading-tight">Lembretes em Áudio</h1>
            <p className="text-[11px] text-slate-400">Repetições periódicas</p>
          </div>
        </div>

        <nav className="space-y-1.5 flex-1">
          {navItems.map((item) => {
            const Icon = item.icon;
            const isSelected = currentScreen === item.screen;
            return (
              <button
                key={item.screen}
                onClick={() => setCurrentScreen(item.screen)}
                className={`w-full flex items-center gap-3 px-3.5 py-3 rounded-2xl text-sm font-semibold transition-all cursor-pointer ${
                  isSelected
                    ? 'bg-blue-600 text-white shadow-lg shadow-blue-600/25'
                    : 'text-slate-400 hover:text-slate-200 hover:bg-slate-800/60'
                }`}
              >
                <Icon className="w-5 h-5" />
                <span>{item.label}</span>
              </button>
            );
          })}
        </nav>

        {/* Quick Record CTA on Sidebar */}
        <div className="pt-4 border-t border-slate-800">
          <button
            onClick={() => setCurrentScreen('RECORD')}
            className="w-full flex items-center justify-center gap-2 py-3 px-4 rounded-2xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold shadow-md transition-all cursor-pointer"
          >
            <Mic className="w-4 h-4" />
            Gravar Novo Áudio
          </button>
        </div>
      </aside>

      {/* Main Content Area */}
      <div className="flex-1 flex flex-col min-w-0">
        {/* Top Header */}
        <header className="sticky top-0 z-30 bg-slate-950/80 backdrop-blur-md border-b border-slate-800/80 px-6 py-4 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="md:hidden p-2 rounded-xl bg-blue-600/30 text-blue-400 border border-blue-500/30">
              <Volume2 className="w-5 h-5" />
            </div>
            <div>
              <h1 className="font-bold text-lg text-slate-100 leading-tight">{getScreenTitle()}</h1>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <span
              className={`inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1 rounded-full border ${
                preferences.isAppMasterEnabled
                  ? 'bg-emerald-950/80 text-emerald-300 border-emerald-800/80'
                  : 'bg-rose-950/80 text-rose-300 border-rose-800/80'
              }`}
            >
              <span
                className={`w-2 h-2 rounded-full ${
                  preferences.isAppMasterEnabled ? 'bg-emerald-500 animate-pulse' : 'bg-rose-500'
                }`}
              />
              {preferences.isAppMasterEnabled ? 'Ativo' : 'Desligado'}
            </span>
          </div>
        </header>

        {/* Screen Content */}
        <main className="flex-1 p-4 md:p-8 overflow-y-auto">
          {currentScreen === 'HOME' && (
            <HomeScreen
              preferences={preferences}
              profiles={profiles}
              audios={audios}
              onUpdatePreferences={handleUpdatePreferences}
              onUpdateProfile={handleSaveProfile}
              onNavigateToLibrary={() => setCurrentScreen('LIBRARY')}
              onNavigateToProfiles={() => setCurrentScreen('PROFILES')}
              onShowToast={showToast}
            />
          )}

          {currentScreen === 'PROFILES' && (
            <ProfilesScreen
              profiles={profiles}
              audios={audios}
              onSaveProfile={handleSaveProfile}
              onDeleteProfile={handleDeleteProfile}
              onToggleProfile={handleToggleProfile}
              onShowToast={showToast}
            />
          )}

          {currentScreen === 'LIBRARY' && (
            <LibraryScreen
              audios={audios}
              reminders={reminders}
              preferences={preferences}
              onAddAudios={handleAddAudios}
              onUpdateAudio={handleUpdateAudio}
              onDeleteAudio={handleDeleteAudio}
              onSaveReminder={handleSaveReminder}
              onDeleteReminder={handleDeleteReminder}
              onNavigateToRecord={() => setCurrentScreen('RECORD')}
              onShowToast={showToast}
            />
          )}

          {currentScreen === 'SETTINGS' && (
            <SettingsScreen
              preferences={preferences}
              onUpdatePreferences={handleUpdatePreferences}
              onReloadAllData={reloadAllData}
              onShowToast={showToast}
            />
          )}

          {currentScreen === 'RECORD' && (
            <RecordReminderScreen
              onSaveAudio={(newAudio) => {
                handleAddAudios([newAudio]);
                setCurrentScreen('LIBRARY');
              }}
              onNavigateBack={() => setCurrentScreen('LIBRARY')}
              onShowToast={showToast}
            />
          )}
        </main>
      </div>

      {/* Floating Global Audio Player Bar */}
      <AudioPlayerBar />

      {/* Bottom Navigation for Mobile Devices */}
      {currentScreen !== 'RECORD' && (
        <nav className="md:hidden fixed bottom-0 left-0 right-0 bg-slate-900/95 backdrop-blur-md border-t border-slate-800 flex items-center justify-around py-2 z-40">
          {navItems.map((item) => {
            const Icon = item.icon;
            const isSelected = currentScreen === item.screen;
            return (
              <button
                key={item.screen}
                onClick={() => setCurrentScreen(item.screen)}
                className={`flex flex-col items-center gap-1 py-1 px-3 rounded-xl transition-colors cursor-pointer ${
                  isSelected ? 'text-blue-400 font-bold' : 'text-slate-400 hover:text-slate-200'
                }`}
              >
                <Icon className={`w-5 h-5 ${isSelected ? 'stroke-[2.5]' : ''}`} />
                <span className="text-[10px]">{item.label}</span>
              </button>
            );
          })}
        </nav>
      )}

      {/* Global Toast Alert */}
      {toastMessage && (
        <div className="fixed top-5 left-1/2 -translate-x-1/2 z-50 bg-slate-900 border border-blue-500/50 text-slate-100 px-4 py-2.5 rounded-2xl shadow-2xl backdrop-blur-md flex items-center gap-2.5 text-xs font-semibold animate-in fade-in slide-in-from-top-3 max-w-md text-center">
          <CheckCircle2 className="w-4 h-4 text-blue-400 flex-shrink-0" />
          <span>{toastMessage}</span>
        </div>
      )}
    </div>
  );
};
