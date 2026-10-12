import React, { useEffect, useState } from 'react';
import { Volume2, Play, Pause, Square, Mic, Music, VolumeX, Sparkles } from 'lucide-react';
import { audioPlayer } from '../services/audioPlayer';
import { AudioItem } from '../types';

export const AudioPlayerBar: React.FC = () => {
  const [isPlaying, setIsPlaying] = useState<boolean>(false);
  const [currentItem, setCurrentItem] = useState<AudioItem | null>(null);
  const [progress, setProgress] = useState<number>(0);
  const [duration, setDuration] = useState<number>(0);

  useEffect(() => {
    const unsub = audioPlayer.subscribe((playing, item, prog, dur) => {
      setIsPlaying(playing);
      setCurrentItem(item);
      setProgress(prog);
      setDuration(dur);
    });
    return unsub;
  }, []);

  if (!currentItem && !isPlaying) {
    return null;
  }

  const formatTime = (secs: number) => {
    if (isNaN(secs) || secs < 0) return '00:00';
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  };

  const handleSeek = (e: React.ChangeEvent<HTMLInputElement>) => {
    const val = parseFloat(e.target.value);
    audioPlayer.seek(val);
  };

  const getTypeIcon = () => {
    if (!currentItem) return <Music className="w-5 h-5 text-purple-400" />;
    switch (currentItem.audioType) {
      case 'RECORDING':
        return <Mic className="w-5 h-5 text-emerald-400" />;
      case 'TEXT_TO_SPEECH':
        return <Sparkles className="w-5 h-5 text-amber-400" />;
      default:
        return <Music className="w-5 h-5 text-purple-400" />;
    }
  };

  return (
    <div className="fixed bottom-16 md:bottom-4 left-4 right-4 max-w-2xl mx-auto bg-slate-900/95 border border-purple-500/40 rounded-2xl shadow-2xl backdrop-blur-md p-3.5 z-50 transition-all animate-in fade-in slide-in-from-bottom-4">
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-3 min-w-0">
          <div className="p-2.5 rounded-xl bg-purple-950/80 border border-purple-800/50 flex-shrink-0 animate-pulse">
            {getTypeIcon()}
          </div>
          <div className="min-w-0">
            <h4 className="text-sm font-semibold text-slate-100 truncate">
              {currentItem?.label || 'Reproduzindo Áudio'}
            </h4>
            <div className="flex items-center gap-2 text-xs text-slate-400">
              <span className="text-purple-400 font-medium">
                {currentItem?.audioType === 'TEXT_TO_SPEECH' ? 'Voz Sintetizada (TTS)' : currentItem?.audioType === 'RECORDING' ? 'Gravação de Voz' : 'Arquivo de Áudio'}
              </span>
              <span>•</span>
              <span>{formatTime(progress)} {duration > 0 ? `/ ${formatTime(duration)}` : ''}</span>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-2 flex-shrink-0">
          <button
            onClick={() => (isPlaying ? audioPlayer.pause() : audioPlayer.resume())}
            className="p-2.5 rounded-xl bg-purple-600 hover:bg-purple-500 text-white transition-colors cursor-pointer shadow-md"
            title={isPlaying ? 'Pausar' : 'Continuar'}
          >
            {isPlaying ? <Pause className="w-4 h-4" /> : <Play className="w-4 h-4 ml-0.5" />}
          </button>
          <button
            onClick={() => audioPlayer.stop()}
            className="p-2.5 rounded-xl bg-slate-800 hover:bg-rose-900/50 hover:text-rose-400 text-slate-400 transition-colors cursor-pointer"
            title="Parar"
          >
            <Square className="w-4 h-4" />
          </button>
        </div>
      </div>

      {duration > 0 && (
        <div className="mt-2.5 flex items-center gap-2">
          <input
            type="range"
            min={0}
            max={duration}
            step={0.1}
            value={progress}
            onChange={handleSeek}
            className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-purple-500"
          />
        </div>
      )}
    </div>
  );
};
