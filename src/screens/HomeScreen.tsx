import React, { useMemo, useState } from 'react';
import {
  Power,
  Volume2,
  Clock,
  AlertTriangle,
  Bell,
  Play,
  RotateCcw,
  CheckCircle2,
  Music,
  Calendar,
  Sparkles,
  Square,
  ArrowRight,
  ShieldAlert,
  ChevronRight
} from 'lucide-react';
import { AudioItem, Profile, ScheduledTrigger, UserPreferences } from '../types';
import { checkDayConflicts, findActiveProfileFor, getNextNTriggers } from '../services/scheduleCalculator';
import { reminderScheduler } from '../services/reminderScheduler';

interface HomeScreenProps {
  preferences: UserPreferences;
  profiles: Profile[];
  audios: AudioItem[];
  onUpdatePreferences: (updates: Partial<UserPreferences>) => void;
  onUpdateProfile: (profile: Profile) => void;
  onNavigateToLibrary: () => void;
  onNavigateToProfiles: () => void;
  onShowToast: (message: string) => void;
}

export const HomeScreen: React.FC<HomeScreenProps> = ({
  preferences,
  profiles,
  audios,
  onUpdatePreferences,
  onUpdateProfile,
  onNavigateToLibrary,
  onNavigateToProfiles,
  onShowToast,
}) => {
  const [showConflictModal, setShowConflictModal] = useState(false);
  const isSnoozed = preferences.snoozeUntilEpochMillis > Date.now();
  const activeAudios = useMemo(() => audios.filter(a => a.isActive && !a.isMissing), [audios]);
  const activeProfileToday = useMemo(() => findActiveProfileFor(new Date(), profiles), [profiles]);
  const conflictWarning = useMemo(() => checkDayConflicts(profiles), [profiles]);

  const nextTriggers: ScheduledTrigger[] = useMemo(() => {
    if (!preferences.isAppMasterEnabled || activeAudios.length === 0) return [];
    return getNextNTriggers(
      new Date(),
      5,
      profiles,
      activeAudios,
      isSnoozed ? new Date(preferences.snoozeUntilEpochMillis) : null,
      preferences.lastSequentialIndex
    );
  }, [preferences, profiles, activeAudios, isSnoozed]);

  const statusText = useMemo(() => {
    const nextTime = nextTriggers[0]?.triggerTime;
    return reminderScheduler.getCurrentStatusText(preferences, profiles, nextTime);
  }, [preferences, profiles, nextTriggers]);

  const handleToggleMaster = (targetChecked: boolean) => {
    // Section 2.1 requirement:
    // "Não permitir ligar o app (nem um perfil) sem pelo menos um áudio ativo,
    // com mensagem orientando o usuário a adicioná-lo."
    if (targetChecked && activeAudios.length === 0) {
      onShowToast('Não é possível ligar o app sem áudios. Adicione um áudio na Biblioteca primeiro!');
      return;
    }

    onUpdatePreferences({ isAppMasterEnabled: targetChecked });
    onShowToast(targetChecked ? 'Lembretes em áudio ativados!' : 'Lembretes desativados.');
  };

  const handleSnooze = (minutes: number) => {
    const snoozeUntil = Date.now() + minutes * 60 * 1000;
    onUpdatePreferences({ snoozeUntilEpochMillis: snoozeUntil });
    onShowToast(`Silenciado por ${minutes >= 60 ? `${minutes / 60} hora(s)` : `${minutes} minutos`}.`);
  };

  const handleResumeSnooze = () => {
    onUpdatePreferences({ snoozeUntilEpochMillis: 0 });
    onShowToast('Reprodução de áudios retomada.');
  };

  const handleStopAll = () => {
    onUpdatePreferences({ isAppMasterEnabled: false, snoozeUntilEpochMillis: 0 });
    onShowToast('Lembretes desligados completamente.');
  };

  const handleTestTrigger = () => {
    if (activeAudios.length === 0) {
      onShowToast('Adicione ou ative ao menos um áudio na Biblioteca primeiro!');
      return;
    }
    const profile = activeProfileToday || profiles[0] || {
      id: 0,
      name: 'Disparo de Teste',
      isEnabled: true,
      priority: 0,
      daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
      startTime: '00:00',
      endTime: '23:59',
      intervalMinutes: 15,
      playbackOrder: 'SEQUENTIAL',
      audioIds: [],
      restingPeriods: [],
      resumePolicy: 'NEXT_GRID_MARK',
      fadeInOverrideEnabled: false,
      fadeInDurationSeconds: 2.0
    };

    reminderScheduler.triggerAudio(
      profile,
      activeAudios,
      preferences,
      () => onUpdatePreferences({ lastSequentialIndex: (preferences.lastSequentialIndex || 0) + 1 })
    );
    onShowToast('Disparando áudio de teste...');
  };

  const requestNotificationPermission = async () => {
    if ('Notification' in window) {
      const res = await Notification.requestPermission();
      if (res === 'granted') {
        onShowToast('Notificações no navegador ativadas com sucesso!');
      }
    }
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-20">
      {/* 1. Master On/Off Switch Card with Fixed Status Info (Section 2.6 & 3.1) */}
      <div
        className={`p-6 rounded-3xl border transition-all duration-300 shadow-xl ${
          preferences.isAppMasterEnabled
            ? 'bg-gradient-to-br from-purple-900/40 via-slate-900 to-indigo-950/40 border-purple-500/30 ring-1 ring-purple-500/20'
            : 'bg-slate-900/90 border-slate-800'
        }`}
      >
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="space-y-1.5">
            <div className="flex items-center gap-2.5">
              <span
                className={`p-2 rounded-xl ${
                  preferences.isAppMasterEnabled
                    ? 'bg-purple-600/30 text-purple-300'
                    : 'bg-slate-800 text-slate-400'
                }`}
              >
                <Power className="w-5 h-5" />
              </span>
              <h2 className="text-xl font-bold text-slate-100">
                {preferences.isAppMasterEnabled ? 'Lembretes Ativos' : 'Lembretes Desativados'}
              </h2>
            </div>
            <p className="text-sm text-slate-400">
              {preferences.isAppMasterEnabled
                ? 'O aplicativo está monitorando horários e agendando reproduções periódicas.'
                : 'Toque no interruptor para ativar o funcionamento contínuo dos lembretes.'}
            </p>
          </div>

          <label className="relative inline-flex items-center cursor-pointer select-none self-start sm:self-center">
            <input
              type="checkbox"
              checked={preferences.isAppMasterEnabled}
              onChange={(e) => handleToggleMaster(e.target.checked)}
              className="sr-only peer"
            />
            <div className="w-14 h-8 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-6 peer-checked:after:border-white after:content-[''] after:absolute after:top-[4px] after:left-[4px] after:bg-white after:rounded-full after:h-6 after:w-6 after:transition-all peer-checked:bg-purple-600 shadow-inner"></div>
          </label>
        </div>

        {/* Section 2.6 Status pill matching "Próximo áudio às HH:mm" / "Em descanso até HH:mm" / "Pausado até HH:mm" */}
        <div className="mt-5 pt-4 border-t border-slate-800/80 flex flex-wrap items-center justify-between gap-3">
          <div className="inline-flex items-center gap-2 px-4 py-2 rounded-2xl text-xs font-semibold bg-slate-950/90 border border-slate-800">
            <span
              className={`w-2.5 h-2.5 rounded-full ${
                !preferences.isAppMasterEnabled
                  ? 'bg-rose-500 ring-2 ring-rose-500/20'
                  : isSnoozed
                  ? 'bg-amber-500 ring-2 ring-amber-500/20 animate-pulse'
                  : 'bg-emerald-500 ring-2 ring-emerald-500/20 animate-pulse'
              }`}
            />
            <span className="text-slate-200">
              {statusText}
            </span>
          </div>

          <div className="flex items-center gap-2">
            {preferences.isAppMasterEnabled && (
              <button
                onClick={handleStopAll}
                className="text-xs font-medium px-3 py-1.5 rounded-xl bg-rose-950/40 hover:bg-rose-900/40 text-rose-300 border border-rose-800/60 flex items-center gap-1.5 transition-colors cursor-pointer"
                title="Desligar tudo"
              >
                <Square className="w-3.5 h-3.5 fill-rose-400" />
                Parar Tudo
              </button>
            )}
            <button
              onClick={handleTestTrigger}
              className="text-xs font-medium px-3 py-1.5 rounded-xl bg-purple-600/20 hover:bg-purple-600/30 text-purple-300 border border-purple-500/30 flex items-center gap-1.5 transition-colors cursor-pointer"
            >
              <Play className="w-3.5 h-3.5" />
              Testar Disparo
            </button>
          </div>
        </div>
      </div>

      {/* 2. Empty Library Warning Alert Banner (Section 2.1) */}
      {activeAudios.length === 0 && (
        <div className="p-6 rounded-3xl bg-rose-950/50 border border-rose-800/60 shadow-lg flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
          <div className="flex items-start gap-3.5">
            <div className="p-3 rounded-2xl bg-rose-900/60 text-rose-300 flex-shrink-0">
              <AlertTriangle className="w-6 h-6" />
            </div>
            <div>
              <h3 className="text-base font-bold text-rose-200">Biblioteca vazia: Nenhum áudio ativo</h3>
              <p className="text-xs text-rose-300/80 mt-1 max-w-xl leading-relaxed">
                O aplicativo começa vazio por padrão e você escolhe todos os áudios. Para ligar o app ou perfis, adicione ao menos um arquivo de áudio, gravação de voz ou frase sintetizada (TTS).
              </p>
            </div>
          </div>
          <button
            onClick={onNavigateToLibrary}
            className="w-full sm:w-auto px-5 py-3 bg-rose-600 hover:bg-rose-500 text-white rounded-2xl text-xs font-bold flex items-center justify-center gap-2 transition-all shadow-md cursor-pointer flex-shrink-0"
          >
            <Music className="w-4 h-4" />
            Adicionar áudios agora
          </button>
        </div>
      )}

      {/* 3. Browser Audio & Notification Banner */}
      {typeof window !== 'undefined' && 'Notification' in window && Notification.permission !== 'granted' && (
        <div className="p-4 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <div className="p-2 rounded-xl bg-indigo-500/20 text-indigo-400">
              <Bell className="w-5 h-5" />
            </div>
            <div>
              <h4 className="text-sm font-semibold text-slate-200">Alertas de Notificação</h4>
              <p className="text-xs text-slate-400">Permita notificações para receber alertas visuais de disparos.</p>
            </div>
          </div>
          <button
            onClick={requestNotificationPermission}
            className="px-3.5 py-1.5 rounded-xl bg-indigo-600 hover:bg-indigo-500 text-white text-xs font-medium cursor-pointer transition-colors"
          >
            Ativar
          </button>
        </div>
      )}

      {/* 4. Conflict Warning & Priority Resolution (Section 2.2) */}
      {conflictWarning && (
        <div className="p-5 rounded-3xl bg-amber-950/40 border border-amber-800/40 flex flex-col sm:flex-row items-start sm:items-center justify-between gap-4">
          <div className="flex items-center gap-3">
            <AlertTriangle className="w-5 h-5 text-amber-400 flex-shrink-0" />
            <p className="text-xs text-amber-200">{conflictWarning}</p>
          </div>
          <button
            onClick={() => setShowConflictModal(true)}
            className="px-4 py-2 rounded-xl bg-amber-600 hover:bg-amber-500 text-white text-xs font-semibold flex items-center gap-1.5 cursor-pointer flex-shrink-0"
          >
            Resolver Prioridade
          </button>
        </div>
      )}

      {/* 5. Silence / Snooze Control Card (Section 2.6) */}
      <div className="p-6 rounded-3xl bg-slate-900 border border-slate-800 shadow-md">
        <h3 className="text-base font-bold text-slate-200 mb-1">Controle de Silêncio (Pausa Rápida)</h3>
        <p className="text-xs text-slate-400 mb-4">
          Silencie os lembretes temporariamente durante reuniões, descansos ou momentos de foco.
        </p>

        {isSnoozed ? (
          <div className="flex flex-col sm:flex-row items-center justify-between gap-4 p-4 rounded-2xl bg-amber-950/30 border border-amber-800/40">
            <div>
              <h4 className="text-sm font-semibold text-amber-300">Em pausa no momento</h4>
              <p className="text-xs text-amber-200/70">
                Pausa até {new Date(preferences.snoozeUntilEpochMillis).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}. Nenhum áudio tocará até o fim do período.
              </p>
            </div>
            <button
              onClick={handleResumeSnooze}
              className="w-full sm:w-auto px-4 py-2 rounded-xl bg-amber-600 hover:bg-amber-500 text-white font-semibold text-xs transition-colors flex items-center justify-center gap-1.5 cursor-pointer"
            >
              <RotateCcw className="w-3.5 h-3.5" />
              Retomar agora
            </button>
          </div>
        ) : (
          <div className="grid grid-cols-3 gap-3">
            <button
              onClick={() => handleSnooze(30)}
              className="py-3 px-2 rounded-xl bg-slate-800 hover:bg-slate-700/80 text-slate-200 text-xs font-semibold transition-colors border border-slate-700/70 cursor-pointer text-center"
            >
              30 minutos
            </button>
            <button
              onClick={() => handleSnooze(60)}
              className="py-3 px-2 rounded-xl bg-purple-600/30 hover:bg-purple-600/40 text-purple-200 text-xs font-semibold transition-colors border border-purple-500/40 cursor-pointer text-center"
            >
              Silenciar 1h
            </button>
            <button
              onClick={() => handleSnooze(120)}
              className="py-3 px-2 rounded-xl bg-slate-800 hover:bg-slate-700/80 text-slate-200 text-xs font-semibold transition-colors border border-slate-700/70 cursor-pointer text-center"
            >
              2 horas
            </button>
          </div>
        )}
      </div>

      {/* 6. Next 5 Triggers List (Section 2.7) */}
      <div className="space-y-3">
        <div className="flex items-center justify-between px-1">
          <div className="flex items-center gap-2">
            <Clock className="w-5 h-5 text-purple-400" />
            <h3 className="text-lg font-bold text-slate-100">Próximos Disparos</h3>
          </div>
          <span className="text-xs text-slate-400 font-medium bg-slate-800/80 px-2.5 py-1 rounded-full border border-slate-700/60">
            {nextTriggers.length} previstos
          </span>
        </div>

        {nextTriggers.length === 0 ? (
          <div className="p-8 rounded-3xl bg-slate-900/60 border border-slate-800 text-center space-y-3">
            <div className="w-12 h-12 rounded-2xl bg-slate-800/80 text-slate-400 mx-auto flex items-center justify-center">
              <Clock className="w-6 h-6" />
            </div>
            <p className="text-sm text-slate-300 font-medium">
              {!preferences.isAppMasterEnabled
                ? 'Ligue o aplicativo no interruptor acima para visualizar os próximos disparos.'
                : activeAudios.length === 0
                ? 'Cadastre áudios na biblioteca para que os disparos possam ser agendados.'
                : 'Nenhum disparo previsto para os próximos dias nos perfis atuais.'}
            </p>
          </div>
        ) : (
          <div className="space-y-2.5">
            {nextTriggers.map((trigger, idx) => {
              const triggerDate = new Date(trigger.triggerTime);
              const timeFormatted = triggerDate.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
              const dateFormatted = triggerDate.toLocaleDateString([], { day: '2-digit', month: '2-digit' });

              return (
                <div
                  key={`${trigger.triggerTime}-${idx}`}
                  className="p-3.5 rounded-2xl bg-slate-900 border border-slate-800/80 hover:border-slate-700 transition-all flex items-center justify-between gap-3 shadow-sm"
                >
                  <div className="flex items-center gap-3.5 min-w-0">
                    <div className="w-14 h-12 rounded-xl bg-purple-950/70 border border-purple-800/50 flex flex-col items-center justify-center flex-shrink-0 text-purple-300">
                      <span className="text-base font-bold leading-none">{timeFormatted}</span>
                      <span className="text-[10px] text-purple-400/80 mt-0.5">{dateFormatted}</span>
                    </div>

                    <div className="min-w-0">
                      <h4 className="text-sm font-semibold text-slate-100 truncate">
                        {trigger.predictedAudioLabel}
                      </h4>
                      <p className="text-xs text-slate-400 truncate">
                        Perfil: <span className="text-slate-300 font-medium">{trigger.profileName}</span>
                      </p>
                    </div>
                  </div>

                  {trigger.isRandomAudio && (
                    <span className="px-2.5 py-1 rounded-full text-[11px] font-semibold bg-indigo-950/80 text-indigo-300 border border-indigo-800/60 flex-shrink-0">
                      Áudio aleatório
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* Conflict Resolution Modal (Section 2.2) */}
      {showConflictModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-in fade-in">
          <div className="w-full max-w-md bg-slate-900 border border-slate-700 rounded-3xl p-6 shadow-2xl space-y-4">
            <h3 className="text-base font-bold text-slate-100">Resolver Conflito de Perfis</h3>
            <p className="text-xs text-slate-400">
              Dois ou mais perfis ativos cobrem os mesmos dias da semana. Escolha qual deve ter maior prioridade para prevalecer:
            </p>

            <div className="space-y-2 max-h-60 overflow-y-auto">
              {profiles.filter(p => p.isEnabled).map((p) => (
                <div
                  key={p.id}
                  className="p-3 rounded-2xl bg-slate-800/60 border border-slate-700 flex items-center justify-between gap-3"
                >
                  <div>
                    <h4 className="text-sm font-semibold text-slate-200">{p.name}</h4>
                    <span className="text-xs text-purple-400">Prioridade atual: {p.priority}</span>
                  </div>
                  <button
                    onClick={() => {
                      onUpdateProfile({ ...p, priority: p.priority + 1 });
                      onShowToast(`Prioridade de "${p.name}" aumentada para ${p.priority + 1}`);
                      setShowConflictModal(false);
                    }}
                    className="px-3 py-1.5 rounded-xl bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold cursor-pointer"
                  >
                    Dar Prioridade (+1)
                  </button>
                </div>
              ))}
            </div>

            <div className="flex justify-end pt-2">
              <button
                onClick={() => setShowConflictModal(false)}
                className="px-4 py-2 rounded-xl bg-slate-800 text-slate-300 text-xs font-medium cursor-pointer"
              >
                Fechar
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
