import React, { useMemo, useState } from 'react';
import {
  Calendar,
  Clock,
  Plus,
  Edit2,
  Copy,
  Trash2,
  Bed,
  Check,
  AlertCircle,
  X,
  Volume2,
  Layers,
  ChevronDown,
  Sparkles,
  AlertTriangle,
  Music
} from 'lucide-react';
import { AudioItem, PlaybackOrder, Profile, RestingPeriod, ResumePolicy } from '../types';
import { doRestsCoverEntireOperatingHours, mergeRestingPeriods, periodCrossesMidnight } from '../services/scheduleCalculator';

interface ProfilesScreenProps {
  profiles: Profile[];
  audios: AudioItem[];
  onSaveProfile: (profile: Profile) => void;
  onDeleteProfile: (id: number) => void;
  onToggleProfile: (id: number, enabled: boolean) => void;
  onShowToast: (message: string) => void;
}

const DAY_LABELS = [
  { day: 1, short: 'S', name: 'Segunda-feira' },
  { day: 2, short: 'T', name: 'Terça-feira' },
  { day: 3, short: 'Q', name: 'Quarta-feira' },
  { day: 4, short: 'Q', name: 'Quinta-feira' },
  { day: 5, short: 'S', name: 'Sexta-feira' },
  { day: 6, short: 'S', name: 'Sábado' },
  { day: 7, short: 'D', name: 'Domingo' },
];

const INTERVAL_SHORTCUTS = [1, 5, 10, 15, 30, 60];

export const ProfilesScreen: React.FC<ProfilesScreenProps> = ({
  profiles,
  audios,
  onSaveProfile,
  onDeleteProfile,
  onToggleProfile,
  onShowToast,
}) => {
  const [editingProfile, setEditingProfile] = useState<Profile | null>(null);
  const [isModalOpen, setIsModalOpen] = useState(false);

  // Form states for modal
  const [name, setName] = useState('');
  const [priority, setPriority] = useState(0);
  const [daysOfWeek, setDaysOfWeek] = useState<number[]>([1, 2, 3, 4, 5]);
  const [startTime, setStartTime] = useState('08:00');
  const [endTime, setEndTime] = useState('18:00');
  const [intervalMinutes, setIntervalMinutes] = useState(15);
  const [playbackOrder, setPlaybackOrder] = useState<PlaybackOrder>('SEQUENTIAL');
  const [specificAudioId, setSpecificAudioId] = useState<number | null>(null);
  const [selectedAudioIds, setSelectedAudioIds] = useState<number[]>([]);
  const [restingPeriods, setRestingPeriods] = useState<RestingPeriod[]>([]);
  const [resumePolicy, setResumePolicy] = useState<ResumePolicy>('NEXT_GRID_MARK');
  const [fadeInOverride, setFadeInOverride] = useState(false);
  const [fadeInDuration, setFadeInDuration] = useState(2.0);

  const activeAudios = useMemo(() => audios.filter(a => a.isActive && !a.isMissing), [audios]);

  // Section 2.2: Check if resting periods cover the whole operating window
  const tempProfileForValidation: Profile = useMemo(() => ({
    id: 0,
    name,
    isEnabled: true,
    priority,
    daysOfWeek,
    startTime,
    endTime,
    intervalMinutes,
    playbackOrder,
    audioIds: selectedAudioIds,
    restingPeriods,
    resumePolicy,
    fadeInOverrideEnabled: fadeInOverride,
    fadeInDurationSeconds: fadeInDuration,
  }), [name, priority, daysOfWeek, startTime, endTime, intervalMinutes, playbackOrder, selectedAudioIds, restingPeriods, resumePolicy, fadeInOverride, fadeInDuration]);

  const restsCoverEntireHours = useMemo(() => {
    return doRestsCoverEntireOperatingHours(tempProfileForValidation);
  }, [tempProfileForValidation]);

  const handleToggleActive = (profileId: number, targetEnabled: boolean) => {
    // Section 2.1:
    // "Não permitir ligar o app (nem um perfil) sem pelo menos um áudio ativo,
    // com mensagem orientando o usuário a adicioná-lo."
    if (targetEnabled && activeAudios.length === 0) {
      onShowToast('Não é possível ativar perfis sem áudios na biblioteca. Adicione um áudio primeiro!');
      return;
    }
    onToggleProfile(profileId, targetEnabled);
  };

  const openCreateModal = () => {
    setEditingProfile(null);
    setName('Novo Perfil');
    setPriority(0);
    setDaysOfWeek([1, 2, 3, 4, 5]);
    setStartTime('08:00');
    setEndTime('18:00');
    setIntervalMinutes(15);
    setPlaybackOrder('SEQUENTIAL');
    setSpecificAudioId(audios[0]?.id || null);
    setSelectedAudioIds([]);
    setRestingPeriods([
      { id: Date.now(), label: 'Almoço', startTime: '12:00', endTime: '13:00' }
    ]);
    setResumePolicy('NEXT_GRID_MARK');
    setFadeInOverride(false);
    setFadeInDuration(2.0);
    setIsModalOpen(true);
  };

  const openEditModal = (profile: Profile) => {
    setEditingProfile(profile);
    setName(profile.name);
    setPriority(profile.priority);
    setDaysOfWeek([...profile.daysOfWeek]);
    setStartTime(profile.startTime);
    setEndTime(profile.endTime);
    setIntervalMinutes(profile.intervalMinutes);
    setPlaybackOrder(profile.playbackOrder);
    setSpecificAudioId(profile.specificAudioId || null);
    setSelectedAudioIds([...profile.audioIds]);
    setRestingPeriods([...profile.restingPeriods]);
    setResumePolicy(profile.resumePolicy);
    setFadeInOverride(profile.fadeInOverrideEnabled);
    setFadeInDuration(profile.fadeInDurationSeconds);
    setIsModalOpen(true);
  };

  const handleDuplicate = (profile: Profile) => {
    const duplicated: Profile = {
      ...profile,
      id: Date.now(),
      name: `${profile.name} (Cópia)`,
      restingPeriods: profile.restingPeriods.map((r, i) => ({ ...r, id: Date.now() + i + 1 })),
    };
    onSaveProfile(duplicated);
    onShowToast(`Perfil "${profile.name}" duplicado com sucesso.`);
  };

  const handleToggleDay = (day: number) => {
    if (daysOfWeek.includes(day)) {
      if (daysOfWeek.length > 1) {
        setDaysOfWeek(daysOfWeek.filter(d => d !== day));
      } else {
        onShowToast('O perfil precisa ter pelo menos um dia ativo.');
      }
    } else {
      setDaysOfWeek([...daysOfWeek, day].sort());
    }
  };

  const handleAddRestPeriod = () => {
    setRestingPeriods([
      ...restingPeriods,
      {
        id: Date.now(),
        label: 'Descanso',
        startTime: '13:00',
        endTime: '14:00'
      }
    ]);
  };

  const handleRemoveRestPeriod = (id: number) => {
    setRestingPeriods(restingPeriods.filter(r => r.id !== id));
  };

  const handleUpdateRestPeriod = (id: number, updates: Partial<RestingPeriod>) => {
    setRestingPeriods(restingPeriods.map(r => r.id === id ? { ...r, ...updates } : r));
  };

  const handleMergeRests = () => {
    const merged = mergeRestingPeriods(restingPeriods);
    setRestingPeriods(merged);
    onShowToast('Descansos mesclados com sucesso!');
  };

  const handleToggleAudioSelection = (audioId: number) => {
    if (selectedAudioIds.includes(audioId)) {
      setSelectedAudioIds(selectedAudioIds.filter(id => id !== audioId));
    } else {
      setSelectedAudioIds([...selectedAudioIds, audioId]);
    }
  };

  const handleSaveModal = (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim()) return;

    // Merge touching or overlapping rests on save
    const cleanRests = mergeRestingPeriods(restingPeriods);

    const profileToSave: Profile = {
      id: editingProfile ? editingProfile.id : Date.now(),
      name: name.trim(),
      isEnabled: editingProfile ? editingProfile.isEnabled : (activeAudios.length > 0),
      priority,
      daysOfWeek,
      startTime,
      endTime,
      intervalMinutes: Math.max(1, intervalMinutes),
      playbackOrder,
      specificAudioId: playbackOrder === 'SPECIFIC' ? specificAudioId : null,
      audioIds: selectedAudioIds,
      restingPeriods: cleanRests,
      resumePolicy,
      fadeInOverrideEnabled: fadeInOverride,
      fadeInDurationSeconds: fadeInDuration,
    };

    onSaveProfile(profileToSave);
    setIsModalOpen(false);
    onShowToast(`Perfil "${profileToSave.name}" salvo com sucesso!`);
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-20">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4">
        <div>
          <h2 className="text-xl font-bold text-slate-100">Perfis de Funcionamento</h2>
          <p className="text-sm text-slate-400">
            Configure horários, dias da semana, intervalos e períodos de descanso personalizados.
          </p>
        </div>
        <button
          onClick={openCreateModal}
          className="px-4 py-2.5 rounded-2xl bg-purple-600 hover:bg-purple-500 text-white font-semibold text-sm flex items-center justify-center gap-2 shadow-lg shadow-purple-600/30 transition-all cursor-pointer self-start sm:self-auto"
        >
          <Plus className="w-4 h-4" />
          Novo Perfil
        </button>
      </div>

      {/* Profiles list */}
      {profiles.length === 0 ? (
        <div className="p-8 rounded-3xl bg-slate-900 border border-slate-800 text-center space-y-4">
          <p className="text-slate-300 font-medium">Nenhum perfil cadastrado.</p>
          <button
            onClick={openCreateModal}
            className="px-5 py-2.5 rounded-xl bg-purple-600 hover:bg-purple-500 text-white text-sm font-semibold transition-colors cursor-pointer"
          >
            Criar primeiro perfil
          </button>
        </div>
      ) : (
        <div className="space-y-4">
          {profiles.map((profile) => (
            <div
              key={profile.id}
              className={`p-5 rounded-3xl border transition-all ${
                profile.isEnabled
                  ? 'bg-slate-900 border-slate-800 shadow-md hover:border-slate-700'
                  : 'bg-slate-950/60 border-slate-800/60 opacity-70'
              }`}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="space-y-1">
                  <div className="flex items-center gap-2.5">
                    <h3 className="text-base font-bold text-slate-100">{profile.name}</h3>
                    <span className="px-2 py-0.5 rounded-full text-[11px] font-semibold bg-purple-950 text-purple-300 border border-purple-800/60">
                      Prioridade {profile.priority}
                    </span>
                    {periodCrossesMidnight(profile.startTime, profile.endTime) && (
                      <span className="px-2 py-0.5 rounded-full text-[11px] font-semibold bg-indigo-950 text-indigo-300 border border-indigo-800/60">
                        Madrugada
                      </span>
                    )}
                  </div>

                  <div className="flex items-center gap-2 text-xs text-slate-400">
                    <Clock className="w-3.5 h-3.5 text-purple-400" />
                    <span>
                      {profile.startTime} até {profile.endTime} • A cada {profile.intervalMinutes} min
                    </span>
                    <span>•</span>
                    <span className="capitalize">
                      {profile.playbackOrder === 'SEQUENTIAL'
                        ? 'Sequencial'
                        : profile.playbackOrder === 'RANDOM'
                        ? 'Aleatório ponderado'
                        : 'Áudio fixo'}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <label className="relative inline-flex items-center cursor-pointer select-none">
                    <input
                      type="checkbox"
                      checked={profile.isEnabled}
                      onChange={(e) => handleToggleActive(profile.id, e.target.checked)}
                      className="sr-only peer"
                    />
                    <div className="w-11 h-6 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-5 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-600"></div>
                  </label>
                </div>
              </div>

              {/* Days of week chips: D S T Q Q S S */}
              <div className="mt-4 flex flex-wrap items-center gap-1.5">
                {DAY_LABELS.map((d) => {
                  const isActive = profile.daysOfWeek.includes(d.day);
                  return (
                    <span
                      key={d.day}
                      className={`w-7 h-7 rounded-lg text-xs font-bold flex items-center justify-center ${
                        isActive
                          ? 'bg-purple-600/30 text-purple-200 border border-purple-500/40'
                          : 'bg-slate-800/40 text-slate-600 border border-transparent'
                      }`}
                      title={d.name}
                    >
                      {d.short}
                    </span>
                  );
                })}
              </div>

              {/* Audio subset summary */}
              {profile.audioIds.length > 0 && (
                <div className="mt-3 flex items-center gap-2 text-xs text-purple-300">
                  <Music className="w-3.5 h-3.5" />
                  <span>Subconjunto ativo: {profile.audioIds.length} áudio(s) selecionado(s)</span>
                </div>
              )}

              {/* Resting periods summary */}
              {profile.restingPeriods.length > 0 && (
                <div className="mt-3.5 pt-3 border-t border-slate-800/70 flex flex-wrap items-center gap-2">
                  <span className="text-xs text-slate-400 flex items-center gap-1">
                    <Bed className="w-3.5 h-3.5 text-amber-400" />
                    Descansos:
                  </span>
                  {profile.restingPeriods.map((rest, i) => (
                    <span
                      key={rest.id || i}
                      className="px-2.5 py-0.5 rounded-md text-xs bg-amber-950/40 text-amber-300 border border-amber-800/40"
                    >
                      {rest.label}: {rest.startTime} - {rest.endTime}
                    </span>
                  ))}
                  <span className="text-[11px] text-slate-500 ml-1">
                    ({profile.resumePolicy === 'AT_REST_END' ? 'Disparo ao término' : 'Próxima grade'})
                  </span>
                </div>
              )}

              {/* Action buttons */}
              <div className="mt-4 pt-3 border-t border-slate-800/60 flex items-center justify-end gap-2">
                <button
                  onClick={() => openEditModal(profile)}
                  className="p-2 rounded-xl text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors text-xs flex items-center gap-1.5 cursor-pointer"
                  title="Editar Perfil"
                >
                  <Edit2 className="w-3.5 h-3.5" />
                  Editar
                </button>
                <button
                  onClick={() => handleDuplicate(profile)}
                  className="p-2 rounded-xl text-slate-400 hover:text-slate-200 hover:bg-slate-800 transition-colors text-xs flex items-center gap-1.5 cursor-pointer"
                  title="Duplicar Perfil"
                >
                  <Copy className="w-3.5 h-3.5" />
                  Duplicar
                </button>
                {profiles.length > 1 && (
                  <button
                    onClick={() => {
                      if (confirm(`Tem certeza que deseja excluir o perfil "${profile.name}"?`)) {
                        onDeleteProfile(profile.id);
                        onShowToast(`Perfil "${profile.name}" excluído.`);
                      }
                    }}
                    className="p-2 rounded-xl text-rose-400 hover:text-rose-300 hover:bg-rose-950/40 transition-colors text-xs flex items-center gap-1.5 cursor-pointer"
                    title="Excluir Perfil"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                    Excluir
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Profile Create / Edit Modal */}
      {isModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-in fade-in">
          <div className="w-full max-w-2xl bg-slate-900 border border-slate-700 rounded-3xl shadow-2xl p-6 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between pb-4 border-b border-slate-800">
              <h3 className="text-lg font-bold text-slate-100">
                {editingProfile ? 'Editar Perfil de Funcionamento' : 'Novo Perfil de Funcionamento'}
              </h3>
              <button
                onClick={() => setIsModalOpen(false)}
                className="p-2 text-slate-400 hover:text-slate-200 rounded-lg hover:bg-slate-800 cursor-pointer"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            {/* Section 2.2 Warning if rests cover entire operating hours */}
            {restsCoverEntireHours && (
              <div className="mt-4 p-3.5 rounded-2xl bg-rose-950/50 border border-rose-800/60 flex items-center gap-3">
                <AlertTriangle className="w-5 h-5 text-rose-400 flex-shrink-0" />
                <p className="text-xs text-rose-200 font-medium">
                  Atenção: Os períodos de descanso configurados cobrem todo o horário de funcionamento deste perfil. O app nunca tocará!
                </p>
              </div>
            )}

            <form onSubmit={handleSaveModal} className="mt-5 space-y-5">
              {/* Name & Priority */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div className="sm:col-span-2">
                  <label className="block text-xs font-semibold uppercase text-slate-300 mb-1">
                    Nome do Perfil *
                  </label>
                  <input
                    type="text"
                    required
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    placeholder="Ex: Trabalho, Foco Matinal, Fim de Semana"
                    className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800 border border-slate-700 text-slate-100 text-sm focus:outline-none focus:border-purple-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold uppercase text-slate-300 mb-1">
                    Prioridade (0 a 10)
                  </label>
                  <input
                    type="number"
                    min={0}
                    max={10}
                    value={priority}
                    onChange={(e) => setPriority(Number(e.target.value))}
                    className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800 border border-slate-700 text-slate-100 text-sm focus:outline-none focus:border-purple-500"
                  />
                </div>
              </div>

              {/* Days of week */}
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-300 mb-2">
                  Dias da Semana (D S T Q Q S S) *
                </label>
                <div className="grid grid-cols-7 gap-1.5 sm:gap-2">
                  {DAY_LABELS.map((d) => {
                    const isSelected = daysOfWeek.includes(d.day);
                    return (
                      <button
                        key={d.day}
                        type="button"
                        onClick={() => handleToggleDay(d.day)}
                        className={`py-2 rounded-xl text-xs font-semibold transition-all cursor-pointer ${
                          isSelected
                            ? 'bg-purple-600 text-white shadow-md shadow-purple-600/30'
                            : 'bg-slate-800 text-slate-400 hover:bg-slate-700/60'
                        }`}
                        title={d.name}
                      >
                        {d.short}
                      </button>
                    );
                  })}
                </div>
              </div>

              {/* Operating Window & Interval */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div>
                  <label className="block text-xs font-semibold uppercase text-slate-300 mb-1">
                    Início da Janela
                  </label>
                  <input
                    type="time"
                    value={startTime}
                    onChange={(e) => setStartTime(e.target.value)}
                    className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800 border border-slate-700 text-slate-100 text-sm focus:outline-none focus:border-purple-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold uppercase text-slate-300 mb-1">
                    Fim da Janela
                  </label>
                  <input
                    type="time"
                    value={endTime}
                    onChange={(e) => setEndTime(e.target.value)}
                    className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800 border border-slate-700 text-slate-100 text-sm focus:outline-none focus:border-purple-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold uppercase text-slate-300 mb-1">
                    Intervalo Fixo (minutos)
                  </label>
                  <input
                    type="number"
                    min={1}
                    value={intervalMinutes}
                    onChange={(e) => setIntervalMinutes(Math.max(1, Number(e.target.value)))}
                    className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800 border border-slate-700 text-slate-100 text-sm focus:outline-none focus:border-purple-500"
                  />
                </div>
              </div>

              {/* Interval shortcuts: 1, 5, 10, 15, 30, 60 (Section 2.2) */}
              <div className="flex flex-wrap items-center gap-1.5 pt-0.5">
                <span className="text-xs text-slate-400 mr-1">Atalhos rápidos:</span>
                {INTERVAL_SHORTCUTS.map((min) => (
                  <button
                    key={min}
                    type="button"
                    onClick={() => setIntervalMinutes(min)}
                    className={`px-2.5 py-1 rounded-lg text-xs font-medium cursor-pointer transition-colors ${
                      intervalMinutes === min
                        ? 'bg-purple-600 text-white'
                        : 'bg-slate-800 text-slate-400 hover:text-slate-200'
                    }`}
                  >
                    {min} min
                  </button>
                ))}
              </div>

              {/* Playback Order */}
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-300 mb-2">
                  Ordem de Execução
                </label>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
                  {[
                    { id: 'SEQUENTIAL', label: 'Sequencial', desc: 'Alterna áudios em ordem' },
                    { id: 'RANDOM', label: 'Aleatória (Pesos)', desc: 'Sorteia respeitando pesos' },
                    { id: 'SPECIFIC', label: 'Áudio Específico', desc: 'Toca sempre o mesmo áudio' },
                  ].map((opt) => (
                    <label
                      key={opt.id}
                      className={`p-3 rounded-xl border flex flex-col gap-1 cursor-pointer transition-colors ${
                        playbackOrder === opt.id
                          ? 'bg-purple-950/40 border-purple-500/60 text-purple-200'
                          : 'bg-slate-800/40 border-slate-700 text-slate-400 hover:bg-slate-800'
                      }`}
                    >
                      <div className="flex items-center justify-between">
                        <span className="text-sm font-semibold text-slate-200">{opt.label}</span>
                        <input
                          type="radio"
                          name="playbackOrder"
                          checked={playbackOrder === opt.id}
                          onChange={() => setPlaybackOrder(opt.id as PlaybackOrder)}
                          className="accent-purple-600"
                        />
                      </div>
                      <span className="text-[11px] text-slate-400">{opt.desc}</span>
                    </label>
                  ))}
                </div>

                {playbackOrder === 'SPECIFIC' && audios.length > 0 && (
                  <div className="mt-3">
                    <label className="block text-xs font-medium text-slate-300 mb-1">
                      Escolha o Áudio Fixo:
                    </label>
                    <select
                      value={specificAudioId || ''}
                      onChange={(e) => setSpecificAudioId(Number(e.target.value))}
                      className="w-full px-3 py-2 rounded-xl bg-slate-800 border border-slate-700 text-slate-100 text-sm focus:outline-none focus:border-purple-500"
                    >
                      {audios.map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.label}
                        </option>
                      ))}
                    </select>
                  </div>
                )}
              </div>

              {/* Audio subset selection (Section 2.2: "áudios do perfil: subconjunto da biblioteca ou todos") */}
              {audios.length > 0 && playbackOrder !== 'SPECIFIC' && (
                <div className="p-3.5 rounded-xl bg-slate-800/40 border border-slate-700/80 space-y-2">
                  <div className="flex items-center justify-between">
                    <div>
                      <h4 className="text-xs font-semibold text-slate-200">Áudios Deste Perfil</h4>
                      <p className="text-[11px] text-slate-400">
                        {selectedAudioIds.length === 0
                          ? 'Todos os áudios ativos da biblioteca serão utilizados'
                          : `${selectedAudioIds.length} áudio(s) selecionado(s) especificamente`}
                      </p>
                    </div>
                    {selectedAudioIds.length > 0 && (
                      <button
                        type="button"
                        onClick={() => setSelectedAudioIds([])}
                        className="text-xs text-purple-400 hover:text-purple-300 cursor-pointer"
                      >
                        Usar Todos
                      </button>
                    )}
                  </div>

                  <div className="max-h-36 overflow-y-auto space-y-1.5 pt-1">
                    {audios.map((a) => {
                      const isChecked = selectedAudioIds.includes(a.id);
                      return (
                        <label
                          key={a.id}
                          className="flex items-center gap-2 p-2 rounded-lg bg-slate-900/60 border border-slate-800 text-xs text-slate-300 hover:bg-slate-900 cursor-pointer"
                        >
                          <input
                            type="checkbox"
                            checked={isChecked}
                            onChange={() => handleToggleAudioSelection(a.id)}
                            className="accent-purple-600 rounded"
                          />
                          <span className="truncate flex-1">{a.label}</span>
                          <span className="text-[10px] text-slate-500">({a.audioType})</span>
                        </label>
                      );
                    })}
                  </div>
                </div>
              )}

              {/* Resting Periods Management (Section 2.2) */}
              <div className="pt-2 border-t border-slate-800">
                <div className="flex items-center justify-between mb-2">
                  <label className="text-xs font-semibold uppercase text-slate-300 flex items-center gap-1.5">
                    <Bed className="w-4 h-4 text-amber-400" />
                    Lista de Descansos (Sem Áudios)
                  </label>
                  <div className="flex items-center gap-2">
                    {restingPeriods.length > 1 && (
                      <button
                        type="button"
                        onClick={handleMergeRests}
                        className="text-xs px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-slate-300 border border-slate-700 cursor-pointer"
                      >
                        Mesclar Sobrepostos
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={handleAddRestPeriod}
                      className="text-xs px-2.5 py-1 rounded-lg bg-slate-800 hover:bg-slate-700 text-purple-300 border border-slate-700 flex items-center gap-1 cursor-pointer"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      Adicionar Descanso
                    </button>
                  </div>
                </div>

                {restingPeriods.length === 0 ? (
                  <p className="text-xs text-slate-500 italic py-2">
                    Nenhum período de descanso cadastrado. O perfil tocará durante toda a janela de horário.
                  </p>
                ) : (
                  <div className="space-y-2">
                    {restingPeriods.map((rest) => (
                      <div
                        key={rest.id}
                        className="flex flex-col sm:flex-row items-center gap-2 p-2.5 rounded-xl bg-slate-800/60 border border-slate-700/60"
                      >
                        <input
                          type="text"
                          value={rest.label}
                          onChange={(e) => handleUpdateRestPeriod(rest.id, { label: e.target.value })}
                          placeholder="Ex: Almoço, Sono"
                          className="w-full sm:w-1/3 px-2.5 py-1.5 rounded-lg bg-slate-900 border border-slate-700 text-xs text-slate-200"
                        />
                        <div className="flex items-center gap-2 w-full sm:w-auto flex-1">
                          <input
                            type="time"
                            value={rest.startTime}
                            onChange={(e) => handleUpdateRestPeriod(rest.id, { startTime: e.target.value })}
                            className="px-2 py-1.5 rounded-lg bg-slate-900 border border-slate-700 text-xs text-slate-200 flex-1"
                          />
                          <span className="text-xs text-slate-400">até</span>
                          <input
                            type="time"
                            value={rest.endTime}
                            onChange={(e) => handleUpdateRestPeriod(rest.id, { endTime: e.target.value })}
                            className="px-2 py-1.5 rounded-lg bg-slate-900 border border-slate-700 text-xs text-slate-200 flex-1"
                          />
                        </div>
                        <button
                          type="button"
                          onClick={() => handleRemoveRestPeriod(rest.id)}
                          className="p-1.5 text-rose-400 hover:bg-rose-950/40 rounded-lg cursor-pointer self-end sm:self-center"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* Resume Policy (Section 2.2 & 2.3) */}
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-300 mb-1.5">
                  Política de Retomada pós-Descanso
                </label>
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
                  <label
                    className={`p-3 rounded-xl border flex items-center gap-2 cursor-pointer ${
                      resumePolicy === 'NEXT_GRID_MARK'
                        ? 'bg-purple-950/40 border-purple-500 text-purple-200'
                        : 'bg-slate-800/40 border-slate-700 text-slate-400'
                    }`}
                  >
                    <input
                      type="radio"
                      name="resumePolicy"
                      checked={resumePolicy === 'NEXT_GRID_MARK'}
                      onChange={() => setResumePolicy('NEXT_GRID_MARK')}
                      className="accent-purple-600"
                    />
                    <div>
                      <p className="font-semibold text-slate-200">Próxima marca da grade (Padrão)</p>
                      <p className="text-[11px] text-slate-400">Aguarda o próximo intervalo exato alinhado</p>
                    </div>
                  </label>

                  <label
                    className={`p-3 rounded-xl border flex items-center gap-2 cursor-pointer ${
                      resumePolicy === 'AT_REST_END'
                        ? 'bg-purple-950/40 border-purple-500 text-purple-200'
                        : 'bg-slate-800/40 border-slate-700 text-slate-400'
                    }`}
                  >
                    <input
                      type="radio"
                      name="resumePolicy"
                      checked={resumePolicy === 'AT_REST_END'}
                      onChange={() => setResumePolicy('AT_REST_END')}
                      className="accent-purple-600"
                    />
                    <div>
                      <p className="font-semibold text-slate-200">Retomar ao fim do descanso</p>
                      <p className="text-[11px] text-slate-400">Dispara exatamente no término do descanso</p>
                    </div>
                  </label>
                </div>
              </div>

              {/* Fade In Override (Section 2.5) */}
              <div className="p-3.5 rounded-xl bg-slate-800/40 border border-slate-700/80 space-y-2">
                <div className="flex items-center justify-between">
                  <div>
                    <h4 className="text-xs font-semibold text-slate-200">Sobrescrever Fade-in Neste Perfil</h4>
                    <p className="text-[11px] text-slate-400">Personaliza a suavização de volume para este perfil</p>
                  </div>
                  <input
                    type="checkbox"
                    checked={fadeInOverride}
                    onChange={(e) => setFadeInOverride(e.target.checked)}
                    className="w-4 h-4 accent-purple-600 cursor-pointer"
                  />
                </div>
                {fadeInOverride && (
                  <div className="pt-2">
                    <div className="flex justify-between text-xs text-slate-300 mb-1">
                      <span>Duração do Fade-in:</span>
                      <span className="font-semibold text-purple-400">{fadeInDuration.toFixed(1)} segundos</span>
                    </div>
                    <input
                      type="range"
                      min={0.5}
                      max={10.0}
                      step={0.5}
                      value={fadeInDuration}
                      onChange={(e) => setFadeInDuration(Number(e.target.value))}
                      className="w-full h-1.5 bg-slate-700 rounded-lg appearance-none accent-purple-500 cursor-pointer"
                    />
                  </div>
                )}
              </div>

              {/* Modal Buttons */}
              <div className="pt-4 border-t border-slate-800 flex items-center justify-end gap-3">
                <button
                  type="button"
                  onClick={() => setIsModalOpen(false)}
                  className="px-4 py-2.5 rounded-xl text-slate-400 hover:text-slate-200 hover:bg-slate-800 text-sm font-medium transition-colors cursor-pointer"
                >
                  Cancelar
                </button>
                <button
                  type="submit"
                  className="px-6 py-2.5 rounded-xl bg-purple-600 hover:bg-purple-500 text-white font-semibold text-sm shadow-lg shadow-purple-600/30 transition-all cursor-pointer"
                >
                  Salvar Perfil
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
