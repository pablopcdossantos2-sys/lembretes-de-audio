import React, { useState } from 'react';
import { Sparkles, Volume2, X, Star } from 'lucide-react';
import { audioPlayer } from '../services/audioPlayer';
import { AudioItem } from '../types';

interface TextToSpeechModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSave: (item: Omit<AudioItem, 'id' | 'dateAdded'>) => void;
}

export const TextToSpeechModal: React.FC<TextToSpeechModalProps> = ({ isOpen, onClose, onSave }) => {
  const [label, setLabel] = useState('');
  const [text, setText] = useState('');
  const [weight, setWeight] = useState(3);
  const [volumePercent, setVolumePercent] = useState(100);
  const [isPreviewing, setIsPreviewing] = useState(false);

  if (!isOpen) return null;

  const handlePreview = async () => {
    if (!text.trim()) return;
    setIsPreviewing(true);
    await audioPlayer.playTTS(text, volumePercent / 100);
    setIsPreviewing(false);
  };

  const handleSave = () => {
    if (!label.trim() || !text.trim()) return;
    onSave({
      label: label.trim(),
      uriString: `tts:${Date.now()}`,
      audioType: 'TEXT_TO_SPEECH',
      ttsText: text.trim(),
      weight,
      volumePercent,
      isActive: true,
      isMissing: false
    });
    setLabel('');
    setText('');
    onClose();
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/75 backdrop-blur-sm animate-in fade-in">
      <div className="w-full max-w-lg bg-slate-900 border border-slate-700 rounded-2xl shadow-2xl p-6 overflow-hidden">
        <div className="flex items-center justify-between pb-4 border-b border-slate-800">
          <div className="flex items-center gap-2.5">
            <div className="p-2 bg-amber-500/20 text-amber-400 rounded-xl">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold text-slate-100">Criar Áudio com Texto (TTS)</h3>
              <p className="text-xs text-slate-400">Gere uma fala sintetizada para tocar nos seus lembretes</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="p-2 text-slate-400 hover:text-slate-200 rounded-lg hover:bg-slate-800 cursor-pointer"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="mt-5 space-y-4">
          <div>
            <label className="block text-xs font-semibold uppercase tracking-wider text-slate-300 mb-1.5">
              Identificação / Nome do Áudio *
            </label>
            <input
              type="text"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder="Ex: Beber água, Alongamento, Postura"
              className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800/80 border border-slate-700 text-slate-100 placeholder-slate-500 focus:outline-none focus:border-purple-500 focus:ring-1 focus:ring-purple-500 text-sm"
            />
          </div>

          <div>
            <label className="block text-xs font-semibold uppercase tracking-wider text-slate-300 mb-1.5">
              Frase ou Afirmação a Ser Falada *
            </label>
            <textarea
              rows={3}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="Ex: Hora de respirar fundo, relaxar a musculatura e beber um gole de água."
              className="w-full px-3.5 py-2.5 rounded-xl bg-slate-800/80 border border-slate-700 text-slate-100 placeholder-slate-500 focus:outline-none focus:border-purple-500 focus:ring-1 focus:ring-purple-500 text-sm resize-none"
            />
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 pt-2">
            <div>
              <label className="block text-xs font-semibold uppercase tracking-wider text-slate-300 mb-1.5">
                Peso no Sorteio (1 a 5)
              </label>
              <div className="flex items-center gap-1.5 p-2 bg-slate-800/60 rounded-xl border border-slate-700/60">
                {[1, 2, 3, 4, 5].map((star) => (
                  <button
                    key={star}
                    type="button"
                    onClick={() => setWeight(star)}
                    className="p-1 cursor-pointer transition-transform hover:scale-110"
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
                <label className="text-xs font-semibold uppercase tracking-wider text-slate-300">
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
        </div>

        <div className="mt-6 flex flex-col sm:flex-row items-center justify-between gap-3 pt-4 border-t border-slate-800">
          <button
            type="button"
            onClick={handlePreview}
            disabled={!text.trim() || isPreviewing}
            className="w-full sm:w-auto px-4 py-2.5 rounded-xl border border-slate-700 hover:bg-slate-800 text-slate-300 flex items-center justify-center gap-2 text-sm font-medium transition-colors disabled:opacity-50 cursor-pointer"
          >
            <Volume2 className="w-4 h-4 text-amber-400" />
            {isPreviewing ? 'Testando voz...' : 'Ouvir Prévia'}
          </button>

          <div className="w-full sm:w-auto flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="flex-1 sm:flex-none px-4 py-2.5 rounded-xl text-slate-400 hover:text-slate-200 hover:bg-slate-800 text-sm font-medium transition-colors cursor-pointer"
            >
              Cancelar
            </button>
            <button
              type="button"
              onClick={handleSave}
              disabled={!label.trim() || !text.trim()}
              className="flex-1 sm:flex-none px-5 py-2.5 rounded-xl bg-purple-600 hover:bg-purple-500 text-white text-sm font-semibold shadow-lg shadow-purple-600/30 transition-all disabled:opacity-50 disabled:pointer-events-none cursor-pointer"
            >
              Salvar Áudio
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};
