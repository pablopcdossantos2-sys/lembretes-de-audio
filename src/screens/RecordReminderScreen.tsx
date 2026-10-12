import React, { useEffect, useRef, useState } from 'react';
import {
  ArrowLeft,
  Mic,
  MicOff,
  Pause,
  Play,
  RotateCcw,
  Save,
  Square,
  Star,
  Volume2,
  CheckCircle2
} from 'lucide-react';
import { AudioRecorderService } from '../services/audioRecorder';
import { WaveformVisualizer } from '../components/WaveformVisualizer';
import { AudioItem } from '../types';
import { saveAudioBlob } from '../services/storage';

interface RecordReminderScreenProps {
  onSaveAudio: (item: AudioItem) => void;
  onNavigateBack: () => void;
  onShowToast: (message: string) => void;
}

type RecordState = 'IDLE' | 'RECORDING' | 'PAUSED' | 'FINISHED';

export const RecordReminderScreen: React.FC<RecordReminderScreenProps> = ({
  onSaveAudio,
  onNavigateBack,
  onShowToast,
}) => {
  const [recordState, setRecordState] = useState<RecordState>('IDLE');
  const [label, setLabel] = useState('');
  const [weight, setWeight] = useState(3);
  const [volumePercent, setVolumePercent] = useState(100);
  const [amplitude, setAmplitude] = useState(0);
  const [durationSec, setDurationSec] = useState(0);

  // Preview state
  const [previewBlob, setPreviewBlob] = useState<Blob | null>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [isPreviewPlaying, setIsPreviewPlaying] = useState(false);

  const recorderRef = useRef<AudioRecorderService | null>(null);
  const timerIntervalRef = useRef<number | null>(null);
  const previewAudioRef = useRef<HTMLAudioElement | null>(null);

  useEffect(() => {
    recorderRef.current = new AudioRecorderService();
    return () => {
      recorderRef.current?.cancel();
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
      if (previewUrl) URL.revokeObjectURL(previewUrl);
    };
  }, []);

  const formatTimer = (totalSec: number) => {
    const m = Math.floor(totalSec / 60);
    const s = Math.floor(totalSec % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const handleStart = async () => {
    if (!recorderRef.current) return;
    setDurationSec(0);
    setAmplitude(0);

    const started = await recorderRef.current.startRecording((amp) => {
      setAmplitude(amp);
    });

    if (started) {
      setRecordState('RECORDING');
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
      timerIntervalRef.current = window.setInterval(() => {
        setDurationSec((prev) => prev + 1);
      }, 1000);
      onShowToast('Microfone ativo gravando áudio...');
    } else {
      onShowToast('Erro ao acessar microfone. Verifique as permissões.');
    }
  };

  const handlePause = () => {
    if (recorderRef.current && recordState === 'RECORDING') {
      recorderRef.current.pause();
      setRecordState('PAUSED');
      if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
    }
  };

  const handleResume = () => {
    if (recorderRef.current && recordState === 'PAUSED') {
      recorderRef.current.resume();
      setRecordState('RECORDING');
      timerIntervalRef.current = window.setInterval(() => {
        setDurationSec((prev) => prev + 1);
      }, 1000);
    }
  };

  const handleStop = async () => {
    if (!recorderRef.current) return;
    if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);

    const blob = await recorderRef.current.stop();
    if (blob) {
      setPreviewBlob(blob);
      const url = URL.createObjectURL(blob);
      setPreviewUrl(url);
      setRecordState('FINISHED');
      onShowToast('Gravação concluída! Você pode ouvir a prévia abaixo.');
    } else {
      setRecordState('IDLE');
    }
  };

  const handleReset = () => {
    recorderRef.current?.cancel();
    if (timerIntervalRef.current) clearInterval(timerIntervalRef.current);
    if (previewAudioRef.current) {
      previewAudioRef.current.pause();
      previewAudioRef.current.currentTime = 0;
    }
    setIsPreviewPlaying(false);
    setPreviewBlob(null);
    if (previewUrl) URL.revokeObjectURL(previewUrl);
    setPreviewUrl(null);
    setDurationSec(0);
    setAmplitude(0);
    setRecordState('IDLE');
  };

  const handleTogglePreview = () => {
    if (!previewUrl) return;

    if (!previewAudioRef.current) {
      previewAudioRef.current = new Audio(previewUrl);
      previewAudioRef.current.onended = () => setIsPreviewPlaying(false);
    }

    if (isPreviewPlaying) {
      previewAudioRef.current.pause();
      setIsPreviewPlaying(false);
    } else {
      previewAudioRef.current.volume = volumePercent / 100;
      previewAudioRef.current.play();
      setIsPreviewPlaying(true);
    }
  };

  const handleSave = async () => {
    if (!previewBlob) return;
    const itemLabel = label.trim() || `Gravação de Voz (${new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })})`;
    const id = Date.now();

    // Persist blob in IndexedDB
    await saveAudioBlob(id, previewBlob);

    const newItem: AudioItem = {
      id,
      uriString: `blob_id:${id}`,
      label: itemLabel,
      weight,
      volumePercent,
      isActive: true,
      audioType: 'RECORDING',
      dateAdded: Date.now()
    };

    onSaveAudio(newItem);
    onShowToast(`Gravação "${itemLabel}" salva na biblioteca!`);
    onNavigateBack();
  };

  return (
    <div className="max-w-2xl mx-auto space-y-6 pb-20">
      {/* Top Header */}
      <div className="flex items-center gap-3">
        <button
          onClick={onNavigateBack}
          className="p-2.5 rounded-2xl bg-slate-800 hover:bg-slate-700 text-slate-300 transition-colors cursor-pointer"
          title="Voltar para Biblioteca"
        >
          <ArrowLeft className="w-5 h-5" />
        </button>
        <div>
          <h2 className="text-xl font-bold text-slate-100">Gravar Lembrete em Áudio</h2>
          <p className="text-xs text-slate-400">Capture sua própria voz para tocar periodicamente.</p>
        </div>
      </div>

      {/* Main Studio Card */}
      <div className="p-6 rounded-3xl bg-slate-900 border border-slate-800 shadow-xl space-y-6">
        {/* State Badge & Timer */}
        <div className="flex flex-col items-center justify-center space-y-2">
          <div
            className={`px-3 py-1 rounded-full text-xs font-semibold flex items-center gap-2 border ${
              recordState === 'RECORDING'
                ? 'bg-rose-950/80 text-rose-300 border-rose-800/80 animate-pulse'
                : recordState === 'PAUSED'
                ? 'bg-amber-950/80 text-amber-300 border-amber-800/80'
                : recordState === 'FINISHED'
                ? 'bg-emerald-950/80 text-emerald-300 border-emerald-800/80'
                : 'bg-slate-800 text-slate-400 border-slate-700'
            }`}
          >
            <span
              className={`w-2 h-2 rounded-full ${
                recordState === 'RECORDING'
                  ? 'bg-rose-500'
                  : recordState === 'PAUSED'
                  ? 'bg-amber-500'
                  : recordState === 'FINISHED'
                  ? 'bg-emerald-500'
                  : 'bg-slate-500'
              }`}
            />
            {recordState === 'RECORDING'
              ? 'Gravando áudio...'
              : recordState === 'PAUSED'
              ? 'Gravação Pausada'
              : recordState === 'FINISHED'
              ? 'Gravação Concluída'
              : 'Pronto para Gravar'}
          </div>

          <div className="text-4xl font-extrabold tracking-wider text-slate-100 font-mono">
            {formatTimer(durationSec)}
          </div>
        </div>

        {/* Waveform Visualizer */}
        <WaveformVisualizer
          amplitude={amplitude}
          isRecording={recordState === 'RECORDING'}
          color={recordState === 'RECORDING' ? '#ec4899' : '#8b5cf6'}
        />

        {/* Record Control Actions */}
        <div className="flex items-center justify-center gap-3 pt-2">
          {recordState === 'IDLE' && (
            <button
              onClick={handleStart}
              className="px-6 py-3.5 rounded-2xl bg-rose-600 hover:bg-rose-500 text-white font-semibold text-sm flex items-center gap-2.5 shadow-lg shadow-rose-600/30 transition-all cursor-pointer active:scale-95"
            >
              <Mic className="w-5 h-5" />
              Iniciar Gravação
            </button>
          )}

          {recordState === 'RECORDING' && (
            <>
              <button
                onClick={handlePause}
                className="p-3.5 rounded-2xl bg-amber-600 hover:bg-amber-500 text-white font-semibold text-sm shadow-md transition-colors cursor-pointer"
                title="Pausar"
              >
                <Pause className="w-5 h-5" />
              </button>
              <button
                onClick={handleStop}
                className="px-6 py-3.5 rounded-2xl bg-slate-800 hover:bg-slate-700 text-rose-400 border border-rose-500/30 font-semibold text-sm flex items-center gap-2 shadow-md transition-colors cursor-pointer"
                title="Concluir Gravação"
              >
                <Square className="w-5 h-5 fill-rose-400" />
                Finalizar
              </button>
            </>
          )}

          {recordState === 'PAUSED' && (
            <>
              <button
                onClick={handleResume}
                className="p-3.5 rounded-2xl bg-purple-600 hover:bg-purple-500 text-white font-semibold text-sm shadow-md transition-colors cursor-pointer"
                title="Continuar"
              >
                <Play className="w-5 h-5 ml-0.5" />
              </button>
              <button
                onClick={handleStop}
                className="px-6 py-3.5 rounded-2xl bg-slate-800 hover:bg-slate-700 text-rose-400 border border-rose-500/30 font-semibold text-sm flex items-center gap-2 shadow-md transition-colors cursor-pointer"
                title="Concluir Gravação"
              >
                <Square className="w-5 h-5 fill-rose-400" />
                Finalizar
              </button>
            </>
          )}

          {recordState === 'FINISHED' && (
            <>
              <button
                onClick={handleTogglePreview}
                className={`px-5 py-3 rounded-2xl text-white font-semibold text-sm flex items-center gap-2 shadow-md transition-colors cursor-pointer ${
                  isPreviewPlaying ? 'bg-amber-600 hover:bg-amber-500' : 'bg-purple-600 hover:bg-purple-500'
                }`}
              >
                {isPreviewPlaying ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4 ml-0.5" />}
                {isPreviewPlaying ? 'Pausar Prévia' : 'Ouvir Prévia'}
              </button>
              <button
                onClick={handleReset}
                className="p-3 rounded-2xl bg-slate-800 hover:bg-slate-700 text-slate-300 transition-colors cursor-pointer"
                title="Gravar Novamente"
              >
                <RotateCcw className="w-4 h-4" />
              </button>
            </>
          )}
        </div>

        {/* Configuration details for the recorded audio */}
        {recordState === 'FINISHED' && (
          <div className="pt-6 border-t border-slate-800 space-y-4 animate-in fade-in">
            <div>
              <label className="block text-xs font-semibold uppercase text-slate-300 mb-1">
                Nome do Áudio Gravado *
              </label>
              <input
                type="text"
                value={label}
                onChange={(e) => setLabel(e.target.value)}
                placeholder="Ex: Minha Afirmação Diária, Lembrete de Alongamento"
                className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800 border border-slate-700 text-slate-100 text-sm focus:outline-none focus:border-purple-500"
              />
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-300 mb-1.5">
                  Peso no Sorteio (1 a 5)
                </label>
                <div className="flex items-center gap-1 p-2 bg-slate-800/80 rounded-xl border border-slate-700">
                  {[1, 2, 3, 4, 5].map((star) => (
                    <button
                      key={star}
                      type="button"
                      onClick={() => setWeight(star)}
                      className="p-1 cursor-pointer hover:scale-110 transition-transform"
                    >
                      <Star
                        className={`w-5 h-5 ${
                          star <= weight ? 'text-amber-400 fill-amber-400' : 'text-slate-600'
                        }`}
                      />
                    </button>
                  ))}
                  <span className="text-xs text-slate-400 ml-2 font-medium">({weight}/5)</span>
                </div>
              </div>

              <div>
                <div className="flex justify-between items-center mb-1.5">
                  <label className="text-xs font-semibold uppercase text-slate-300">
                    Volume Próprio
                  </label>
                  <span className="text-xs font-semibold text-purple-400">{volumePercent}%</span>
                </div>
                <input
                  type="range"
                  min={10}
                  max={100}
                  value={volumePercent}
                  onChange={(e) => setVolumePercent(Number(e.target.value))}
                  className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-purple-500 mt-2"
                />
              </div>
            </div>

            <div className="pt-2 flex justify-end">
              <button
                onClick={handleSave}
                className="w-full sm:w-auto px-6 py-3 rounded-2xl bg-emerald-600 hover:bg-emerald-500 text-white font-semibold text-sm flex items-center justify-center gap-2 shadow-lg shadow-emerald-600/30 transition-all cursor-pointer"
              >
                <Save className="w-4 h-4" />
                Salvar na Biblioteca
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
