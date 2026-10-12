import React, { useRef, useState } from 'react';
import {
  Music,
  Plus,
  Mic,
  Sparkles,
  Volume2,
  Trash2,
  Edit3,
  Star,
  Play,
  Pause,
  Upload,
  Bell,
  Check,
  X,
  Clock,
  Calendar,
  AlertTriangle,
  RefreshCw,
  FolderOpen
} from 'lucide-react';
import { AudioItem, ReminderEntity, UserPreferences } from '../types';
import { audioPlayer } from '../services/audioPlayer';
import { TextToSpeechModal } from '../components/TextToSpeechModal';
import { getAudioBlob, saveAudioBlob } from '../services/storage';

interface LibraryScreenProps {
  audios: AudioItem[];
  reminders: ReminderEntity[];
  preferences: UserPreferences;
  onAddAudios: (newAudios: AudioItem[]) => void;
  onUpdateAudio: (audio: AudioItem) => void;
  onDeleteAudio: (id: number) => void;
  onSaveReminder: (reminder: ReminderEntity) => void;
  onDeleteReminder: (id: number) => void;
  onNavigateToRecord: () => void;
  onShowToast: (message: string) => void;
}

export const LibraryScreen: React.FC<LibraryScreenProps> = ({
  audios,
  reminders,
  preferences,
  onAddAudios,
  onUpdateAudio,
  onDeleteAudio,
  onSaveReminder,
  onDeleteReminder,
  onNavigateToRecord,
  onShowToast,
}) => {
  const [activeTab, setActiveTab] = useState<'audios' | 'reminders'>('audios');
  const [showTtsModal, setShowTtsModal] = useState(false);
  const [playingAudioId, setPlayingAudioId] = useState<number | null>(null);

  // Edit Audio State
  const [editingAudio, setEditingAudio] = useState<AudioItem | null>(null);
  const [editLabel, setEditLabel] = useState('');

  // Reassociate missing file state
  const [reassociatingAudioId, setReassociatingAudioId] = useState<number | null>(null);

  // Reminder Create Modal
  const [showReminderModal, setShowReminderModal] = useState(false);
  const [remTitle, setRemTitle] = useState('');
  const [remText, setRemText] = useState('');
  const [remPeriodicity, setRemPeriodicity] = useState(15);
  const [remStartHour, setRemStartHour] = useState(8);
  const [remEndHour, setRemEndHour] = useState(20);

  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const reassociateInputRef = useRef<HTMLInputElement | null>(null);

  const handlePlayToggle = async (audio: AudioItem) => {
    if (audio.isMissing) {
      onShowToast('Arquivo não encontrado no dispositivo. Por favor, reassocie o arquivo.');
      return;
    }

    if (playingAudioId === audio.id) {
      audioPlayer.stop();
      setPlayingAudioId(null);
    } else {
      setPlayingAudioId(audio.id);
      await audioPlayer.play(audio, preferences, {
        onEnded: () => setPlayingAudioId(null)
      });
    }
  };

  const handleFileUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files || files.length === 0) return;

    const newItems: AudioItem[] = [];

    for (let i = 0; i < files.length; i++) {
      const file = files[i];
      const id = Date.now() + i;
      const cleanName = file.name.replace(/\.[^/.]+$/, '');

      // Store in IndexedDB
      await saveAudioBlob(id, file);

      newItems.push({
        id,
        uriString: `blob_id:${id}`,
        label: cleanName || 'Áudio Importado',
        weight: 3,
        volumePercent: 100,
        isActive: true,
        audioType: 'AUDIO_FILE',
        isMissing: false,
        dateAdded: Date.now() + i
      });
    }

    onAddAudios(newItems);
    onShowToast(`${newItems.length} arquivo(s) de áudio adicionado(s) à biblioteca!`);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleReassociateFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file || !reassociatingAudioId) return;

    const targetAudio = audios.find(a => a.id === reassociatingAudioId);
    if (targetAudio) {
      await saveAudioBlob(targetAudio.id, file);
      onUpdateAudio({
        ...targetAudio,
        isMissing: false,
        uriString: `blob_id:${targetAudio.id}`
      });
      onShowToast(`Arquivo para "${targetAudio.label}" reassociado com sucesso!`);
    }
    setReassociatingAudioId(null);
    if (reassociateInputRef.current) reassociateInputRef.current.value = '';
  };

  const handleSaveTts = (itemData: Omit<AudioItem, 'id' | 'dateAdded'>) => {
    const newItem: AudioItem = {
      ...itemData,
      id: Date.now(),
      dateAdded: Date.now()
    };
    onAddAudios([newItem]);
    onShowToast('Áudio de Texto (TTS) adicionado à biblioteca!');
  };

  const handleSaveEditLabel = () => {
    if (editingAudio && editLabel.trim()) {
      onUpdateAudio({ ...editingAudio, label: editLabel.trim() });
      setEditingAudio(null);
      onShowToast('Nome do áudio atualizado!');
    }
  };

  const handleSaveReminderModal = (e: React.FormEvent) => {
    e.preventDefault();
    if (!remTitle.trim()) return;

    const newRem: ReminderEntity = {
      id: Date.now(),
      title: remTitle.trim(),
      text: remText.trim() || null,
      audioUri: null,
      periodicityMinutes: remPeriodicity,
      daysOfWeek: [1, 2, 3, 4, 5, 6, 7],
      startHour: remStartHour,
      startMinute: 0,
      endHour: remEndHour,
      endMinute: 0,
      isEnabled: true,
      audioType: 'TEXT_TO_SPEECH',
      volumePercent: 100,
      createdAt: Date.now()
    };
    onSaveReminder(newRem);
    setShowReminderModal(false);
    setRemTitle('');
    setRemText('');
    onShowToast(`Lembrete "${newRem.title}" salvo!`);
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-20">
      <input
        ref={fileInputRef}
        type="file"
        multiple
        accept="audio/*"
        onChange={handleFileUpload}
        className="hidden"
      />

      <input
        ref={reassociateInputRef}
        type="file"
        accept="audio/*"
        onChange={handleReassociateFile}
        className="hidden"
      />

      {/* Tabs Row */}
      <div className="flex border-b border-slate-800">
        <button
          onClick={() => setActiveTab('audios')}
          className={`flex items-center gap-2 px-6 py-3 border-b-2 text-sm font-semibold transition-colors cursor-pointer ${
            activeTab === 'audios'
              ? 'border-purple-500 text-purple-400'
              : 'border-transparent text-slate-400 hover:text-slate-200'
          }`}
        >
          <Music className="w-4 h-4" />
          Áudios ({audios.length})
        </button>
        <button
          onClick={() => setActiveTab('reminders')}
          className={`flex items-center gap-2 px-6 py-3 border-b-2 text-sm font-semibold transition-colors cursor-pointer ${
            activeTab === 'reminders'
              ? 'border-purple-500 text-purple-400'
              : 'border-transparent text-slate-400 hover:text-slate-200'
          }`}
        >
          <Bell className="w-4 h-4" />
          Lembretes Room ({reminders.length})
        </button>
      </div>

      {activeTab === 'audios' ? (
        <div className="space-y-6">
          {/* Header Action Bar */}
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div>
              <h2 className="text-xl font-bold text-slate-100">Biblioteca de Áudios</h2>
              <p className="text-xs text-slate-400">
                Seus arquivos de voz, gravações do microfone e frases de texto sintetizadas.
              </p>
            </div>

            <div className="flex flex-wrap items-center gap-2">
              <button
                onClick={() => fileInputRef.current?.click()}
                className="px-3.5 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold flex items-center gap-1.5 transition-colors cursor-pointer shadow-md"
              >
                <Upload className="w-4 h-4" />
                Adicionar Áudios
              </button>
              <button
                onClick={onNavigateToRecord}
                className="px-3.5 py-2 rounded-xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold flex items-center gap-1.5 transition-colors cursor-pointer shadow-md"
              >
                <Mic className="w-4 h-4" />
                Gravar Voz
              </button>
              <button
                onClick={() => setShowTtsModal(true)}
                className="px-3.5 py-2 rounded-xl bg-amber-600 hover:bg-amber-500 text-white text-xs font-semibold flex items-center gap-1.5 transition-colors cursor-pointer shadow-md"
              >
                <Sparkles className="w-4 h-4" />
                Texto (TTS)
              </button>
            </div>
          </div>

          {/* Section 2.1 Empty State:
              "O app não vem com nenhum áudio embutido. Por padrão, a biblioteca começa vazia
              e é o usuário quem insere todos os áudios necessários. Na primeira abertura e sempre
              que a biblioteca estiver vazia, mostrar uma tela de estado vazio com explicação
              e um botão claro 'Adicionar áudios'." */}
          {audios.length === 0 ? (
            <div className="p-10 rounded-3xl bg-slate-900 border border-slate-800 text-center space-y-4 max-w-lg mx-auto shadow-xl">
              <div className="w-20 h-20 rounded-full bg-purple-950/80 text-purple-400 mx-auto flex items-center justify-center border border-purple-800/40">
                <Music className="w-10 h-10" />
              </div>
              <h3 className="text-lg font-bold text-slate-100">Biblioteca Vazia</h3>
              <p className="text-sm text-slate-400 leading-relaxed">
                O aplicativo não vem com nenhum áudio embutido. Você escolhe e insere seus próprios arquivos (MP3, WAV, OGG, M4A), grava sua voz no microfone ou cria frases sintetizadas (TTS).
              </p>
              <div className="flex flex-wrap justify-center gap-2 pt-3">
                <button
                  onClick={() => fileInputRef.current?.click()}
                  className="px-5 py-3 rounded-2xl bg-purple-600 hover:bg-purple-500 text-white text-xs font-bold flex items-center gap-2 cursor-pointer shadow-lg shadow-purple-600/25"
                >
                  <Plus className="w-4 h-4" />
                  Adicionar áudios
                </button>
                <button
                  onClick={onNavigateToRecord}
                  className="px-4 py-3 rounded-2xl bg-emerald-600 hover:bg-emerald-500 text-white text-xs font-semibold flex items-center gap-2 cursor-pointer shadow-md"
                >
                  <Mic className="w-4 h-4" />
                  Gravar
                </button>
                <button
                  onClick={() => setShowTtsModal(true)}
                  className="px-4 py-3 rounded-2xl bg-amber-600 hover:bg-amber-500 text-white text-xs font-semibold flex items-center gap-2 cursor-pointer shadow-md"
                >
                  <Sparkles className="w-4 h-4" />
                  Texto (TTS)
                </button>
              </div>
            </div>
          ) : (
            <div className="space-y-3">
              {audios.map((audio) => {
                const isPlaying = playingAudioId === audio.id;
                return (
                  <div
                    key={audio.id}
                    className={`p-4 rounded-2xl border transition-all ${
                      audio.isMissing
                        ? 'bg-rose-950/30 border-rose-800/50'
                        : audio.isActive
                        ? 'bg-slate-900 border-slate-800 hover:border-slate-700 shadow-sm'
                        : 'bg-slate-950/60 border-slate-800/60 opacity-60'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex items-center gap-3.5 min-w-0">
                        {/* Play / Pause button */}
                        <button
                          onClick={() => handlePlayToggle(audio)}
                          className={`w-11 h-11 rounded-2xl flex items-center justify-center flex-shrink-0 transition-transform active:scale-95 cursor-pointer shadow-md ${
                            audio.isMissing
                              ? 'bg-slate-800 text-slate-600 opacity-60 cursor-not-allowed'
                              : isPlaying
                              ? 'bg-purple-500 text-white animate-pulse'
                              : 'bg-slate-800 hover:bg-slate-700 text-purple-300'
                          }`}
                          title={audio.isMissing ? 'Arquivo ausente' : isPlaying ? 'Pausar' : 'Ouvir'}
                        >
                          {isPlaying ? <Pause className="w-5 h-5" /> : <Play className="w-5 h-5 ml-0.5" />}
                        </button>

                        <div className="min-w-0">
                          <div className="flex items-center gap-2">
                            <h4 className="text-sm font-semibold text-slate-100 truncate">
                              {audio.label}
                            </h4>
                            <span
                              className={`px-2 py-0.5 rounded-full text-[10px] font-semibold flex-shrink-0 ${
                                audio.audioType === 'RECORDING'
                                  ? 'bg-emerald-950 text-emerald-300 border border-emerald-800/60'
                                  : audio.audioType === 'TEXT_TO_SPEECH'
                                  ? 'bg-amber-950 text-amber-300 border border-amber-800/60'
                                  : 'bg-purple-950 text-purple-300 border border-purple-800/60'
                              }`}
                            >
                              {audio.audioType === 'RECORDING'
                                ? 'Gravação'
                                : audio.audioType === 'TEXT_TO_SPEECH'
                                ? 'TTS'
                                : 'Arquivo'}
                            </span>

                            {/* Section 2.1 & 2.8: Missing file warning badge */}
                            {audio.isMissing && (
                              <span className="px-2 py-0.5 rounded-full text-[10px] font-bold bg-rose-950 text-rose-300 border border-rose-800 flex items-center gap-1">
                                <AlertTriangle className="w-3 h-3" />
                                Arquivo não encontrado
                              </span>
                            )}
                          </div>

                          {audio.ttsText && (
                            <p className="text-xs text-slate-400 italic truncate mt-0.5">
                              "{audio.ttsText}"
                            </p>
                          )}
                        </div>
                      </div>

                      {/* Right controls */}
                      <div className="flex items-center gap-2.5 flex-shrink-0">
                        {/* Reassociate button if missing */}
                        {audio.isMissing && (
                          <button
                            onClick={() => {
                              setReassociatingAudioId(audio.id);
                              reassociateInputRef.current?.click();
                            }}
                            className="px-3 py-1.5 rounded-xl bg-rose-600 hover:bg-rose-500 text-white text-xs font-semibold flex items-center gap-1.5 cursor-pointer shadow-md"
                            title="Reassociar arquivo do sistema"
                          >
                            <RefreshCw className="w-3.5 h-3.5" />
                            Reassociar
                          </button>
                        )}

                        {/* Active toggle */}
                        <label className="relative inline-flex items-center cursor-pointer select-none">
                          <input
                            type="checkbox"
                            checked={audio.isActive}
                            onChange={(e) => onUpdateAudio({ ...audio, isActive: e.target.checked })}
                            className="sr-only peer"
                          />
                          <div className="w-9 h-5 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-4 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-purple-600"></div>
                        </label>

                        {/* Edit label */}
                        <button
                          onClick={() => {
                            setEditingAudio(audio);
                            setEditLabel(audio.label);
                          }}
                          className="p-2 text-slate-400 hover:text-slate-200 hover:bg-slate-800 rounded-lg cursor-pointer"
                          title="Renomear rótulo"
                        >
                          <Edit3 className="w-4 h-4" />
                        </button>

                        {/* Delete */}
                        <button
                          onClick={() => {
                            if (confirm(`Excluir áudio "${audio.label}"?`)) {
                              onDeleteAudio(audio.id);
                              onShowToast('Áudio excluído.');
                            }
                          }}
                          className="p-2 text-rose-400 hover:text-rose-300 hover:bg-rose-950/40 rounded-lg cursor-pointer"
                          title="Remover"
                        >
                          <Trash2 className="w-4 h-4" />
                        </button>
                      </div>
                    </div>

                    {/* Weight & Volume Controls Row (Section 2.1) */}
                    <div className="mt-3 pt-3 border-t border-slate-800/60 flex flex-wrap items-center justify-between gap-4 text-xs">
                      {/* Weight rating (1 to 5) */}
                      <div className="flex items-center gap-2">
                        <span className="text-slate-400 font-medium">Peso (Sorteio):</span>
                        <div className="flex items-center gap-0.5">
                          {[1, 2, 3, 4, 5].map((star) => (
                            <button
                              key={star}
                              onClick={() => onUpdateAudio({ ...audio, weight: star })}
                              className="p-0.5 cursor-pointer hover:scale-110 transition-transform"
                            >
                              <Star
                                className={`w-4 h-4 ${
                                  star <= audio.weight
                                    ? 'text-amber-400 fill-amber-400'
                                    : 'text-slate-700'
                                }`}
                              />
                            </button>
                          ))}
                        </div>
                      </div>

                      {/* Individual Volume (0 to 100%) */}
                      <div className="flex items-center gap-2 min-w-[140px]">
                        <Volume2 className="w-3.5 h-3.5 text-slate-400" />
                        <span className="text-slate-400">Volume individual: {audio.volumePercent}%</span>
                        <input
                          type="range"
                          min={0}
                          max={100}
                          value={audio.volumePercent}
                          onChange={(e) => onUpdateAudio({ ...audio, volumePercent: Number(e.target.value) })}
                          className="w-20 h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-purple-500"
                        />
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          )}
        </div>
      ) : (
        /* Reminders Room Tab */
        <div className="space-y-6">
          <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div>
              <h2 className="text-xl font-bold text-slate-100">Lembretes Room</h2>
              <p className="text-xs text-slate-400">
                Lembretes individuais pontuais ou periódicos sincronizados.
              </p>
            </div>
            <button
              onClick={() => setShowReminderModal(true)}
              className="px-4 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold flex items-center gap-1.5 transition-colors cursor-pointer shadow-md self-start sm:self-auto"
            >
              <Plus className="w-4 h-4" />
              Novo Lembrete
            </button>
          </div>

          {reminders.length === 0 ? (
            <div className="p-8 rounded-3xl bg-slate-900 border border-slate-800 text-center space-y-3">
              <p className="text-slate-400 text-sm">Nenhum lembrete registrado no banco Room local.</p>
              <button
                onClick={() => setShowReminderModal(true)}
                className="px-4 py-2 bg-purple-600 hover:bg-purple-500 text-white rounded-xl text-xs font-semibold cursor-pointer"
              >
                Criar primeiro lembrete
              </button>
            </div>
          ) : (
            <div className="space-y-3">
              {reminders.map((rem) => (
                <div
                  key={rem.id}
                  className="p-4 rounded-2xl bg-slate-900 border border-slate-800 flex items-center justify-between gap-3"
                >
                  <div className="space-y-1 min-w-0">
                    <div className="flex items-center gap-2">
                      <h4 className="text-sm font-semibold text-slate-100">{rem.title}</h4>
                      <span className="px-2 py-0.5 rounded-full text-[10px] bg-purple-950 text-purple-300 border border-purple-800/60 font-semibold">
                        A cada {rem.periodicityMinutes} min
                      </span>
                    </div>
                    {rem.text && <p className="text-xs text-slate-400 truncate">{rem.text}</p>}
                    <p className="text-[11px] text-slate-500">
                      Janela ativa: {rem.startHour}:00 às {rem.endHour}:00
                    </p>
                  </div>

                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => {
                        if (rem.text) {
                          audioPlayer.playTTS(rem.text, rem.volumePercent / 100);
                        } else {
                          audioPlayer.playTTS(rem.title, rem.volumePercent / 100);
                        }
                      }}
                      className="p-2 text-purple-400 hover:bg-purple-950/40 rounded-lg cursor-pointer"
                      title="Ouvir Lembrete"
                    >
                      <Play className="w-4 h-4" />
                    </button>
                    <button
                      onClick={() => {
                        if (confirm(`Excluir lembrete "${rem.title}"?`)) {
                          onDeleteReminder(rem.id);
                          onShowToast('Lembrete excluído.');
                        }
                      }}
                      className="p-2 text-rose-400 hover:bg-rose-950/40 rounded-lg cursor-pointer"
                      title="Excluir"
                    >
                      <Trash2 className="w-4 h-4" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}

      {/* TTS Modal */}
      <TextToSpeechModal
        isOpen={showTtsModal}
        onClose={() => setShowTtsModal(false)}
        onSave={handleSaveTts}
      />

      {/* Edit Audio Label Dialog */}
      {editingAudio && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-in fade-in">
          <div className="w-full max-w-sm bg-slate-900 border border-slate-700 rounded-2xl p-5 shadow-2xl">
            <h3 className="text-sm font-bold text-slate-100 mb-3">Renomear Rótulo do Áudio</h3>
            <input
              type="text"
              value={editLabel}
              onChange={(e) => setEditLabel(e.target.value)}
              placeholder="Ex: Meta do ano"
              className="w-full px-3 py-2 rounded-xl bg-slate-800 border border-slate-700 text-sm text-slate-100 focus:outline-none focus:border-purple-500 mb-4"
            />
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setEditingAudio(null)}
                className="px-3 py-1.5 rounded-lg text-xs text-slate-400 hover:text-slate-200 cursor-pointer"
              >
                Cancelar
              </button>
              <button
                onClick={handleSaveEditLabel}
                className="px-4 py-1.5 rounded-lg bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold cursor-pointer shadow-md"
              >
                Salvar Rótulo
              </button>
            </div>
          </div>
        </div>
      )}

      {/* New Reminder Room Modal */}
      {showReminderModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-in fade-in">
          <div className="w-full max-w-md bg-slate-900 border border-slate-700 rounded-2xl p-6 shadow-2xl">
            <div className="flex items-center justify-between pb-3 border-b border-slate-800 mb-4">
              <h3 className="text-base font-bold text-slate-100">Novo Lembrete Room</h3>
              <button onClick={() => setShowReminderModal(false)} className="text-slate-400">
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleSaveReminderModal} className="space-y-3.5">
              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Título *</label>
                <input
                  type="text"
                  required
                  value={remTitle}
                  onChange={(e) => setRemTitle(e.target.value)}
                  placeholder="Ex: Beber Água"
                  className="w-full px-3 py-2 rounded-xl bg-slate-800 border border-slate-700 text-sm text-slate-100 focus:outline-none focus:border-purple-500"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-300 mb-1">Texto para Fala (TTS)</label>
                <textarea
                  rows={2}
                  value={remText}
                  onChange={(e) => setRemText(e.target.value)}
                  placeholder="Ex: Hora de se hidratar e alongar a coluna."
                  className="w-full px-3 py-2 rounded-xl bg-slate-800 border border-slate-700 text-sm text-slate-100 focus:outline-none focus:border-purple-500 resize-none"
                />
              </div>

              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-semibold text-slate-300 mb-1">Periodicidade (min)</label>
                  <input
                    type="number"
                    min={1}
                    max={240}
                    value={remPeriodicity}
                    onChange={(e) => setRemPeriodicity(Number(e.target.value))}
                    className="w-full px-3 py-2 rounded-xl bg-slate-800 border border-slate-700 text-sm text-slate-100 focus:outline-none focus:border-purple-500"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-300 mb-1">Horário (Início - Fim)</label>
                  <div className="flex items-center gap-1">
                    <input
                      type="number"
                      min={0}
                      max={23}
                      value={remStartHour}
                      onChange={(e) => setRemStartHour(Number(e.target.value))}
                      className="w-14 px-2 py-2 rounded-xl bg-slate-800 border border-slate-700 text-xs text-center text-slate-100"
                    />
                    <span className="text-xs text-slate-500">às</span>
                    <input
                      type="number"
                      min={0}
                      max={23}
                      value={remEndHour}
                      onChange={(e) => setRemEndHour(Number(e.target.value))}
                      className="w-14 px-2 py-2 rounded-xl bg-slate-800 border border-slate-700 text-xs text-center text-slate-100"
                    />
                  </div>
                </div>
              </div>

              <div className="pt-3 border-t border-slate-800 flex justify-end gap-2">
                <button
                  type="button"
                  onClick={() => setShowReminderModal(false)}
                  className="px-4 py-2 rounded-xl text-xs text-slate-400 hover:text-slate-200"
                >
                  Cancelar
                </button>
                <button
                  type="submit"
                  className="px-5 py-2 rounded-xl bg-purple-600 hover:bg-purple-500 text-white text-xs font-semibold shadow-md"
                >
                  Salvar Lembrete
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};
