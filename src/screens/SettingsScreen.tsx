import React, { useRef, useState } from 'react';
import {
  Volume2,
  Sliders,
  Bell,
  Vibrate,
  ShieldAlert,
  Download,
  Upload,
  RefreshCw,
  Play,
  RotateCcw,
  Check,
  Info,
  Clock,
  Laptop,
  Headphones,
  PhoneCall,
  Moon,
  Sun,
  BatteryWarning,
  HelpCircle
} from 'lucide-react';
import { BackupData, UserPreferences } from '../types';
import { exportBackupData, importBackupDataAsync, resetAllData } from '../services/storage';
import { audioPlayer } from '../services/audioPlayer';

interface SettingsScreenProps {
  preferences: UserPreferences;
  onUpdatePreferences: (updates: Partial<UserPreferences>) => void;
  onReloadAllData: () => void;
  onShowToast: (message: string) => void;
}

export const SettingsScreen: React.FC<SettingsScreenProps> = ({
  preferences,
  onUpdatePreferences,
  onReloadAllData,
  onShowToast,
}) => {
  const [notificationPermission, setNotificationPermission] = useState<string>(
    typeof window !== 'undefined' && 'Notification' in window ? Notification.permission : 'default'
  );
  const [showBatteryAssistant, setShowBatteryAssistant] = useState(false);

  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const handleExportBackup = () => {
    try {
      const data = exportBackupData();
      const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `lembretes-audio-backup-${new Date().toISOString().slice(0, 10)}.json`;
      a.click();
      URL.revokeObjectURL(url);
      onShowToast('Arquivo de backup JSON exportado com sucesso!');
    } catch (err: any) {
      onShowToast(`Erro ao exportar: ${err?.message || 'Falha'}`);
    }
  };

  const handleImportBackup = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const reader = new FileReader();
    reader.onload = async (event) => {
      try {
        const json = JSON.parse(event.target?.result as string) as BackupData;
        const res = await importBackupDataAsync(json);
        onReloadAllData();
        onShowToast(res.message);
      } catch (err) {
        onShowToast('Arquivo JSON inválido ou corrompido.');
      }
    };
    reader.readAsText(file);
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleResetData = () => {
    if (confirm('Tem certeza de que deseja restaurar as configurações originais e limpar os dados?')) {
      resetAllData();
      onReloadAllData();
      onShowToast('Configurações e dados restaurados para o padrão.');
    }
  };

  const handleRequestNotification = async () => {
    if ('Notification' in window) {
      const res = await Notification.requestPermission();
      setNotificationPermission(res);
      if (res === 'granted') {
        onShowToast('Notificações ativadas com sucesso!');
      } else {
        onShowToast('Permissão de notificações recusada.');
      }
    }
  };

  const handleTestFadeIn = async () => {
    onShowToast(`Testando áudio com fade-in de ${preferences.fadeInDurationSeconds.toFixed(1)}s...`);
    await audioPlayer.playTTS(
      'Este é um teste de áudio com fade-in suave configurado.',
      preferences.masterVolumePercent / 100
    );
  };

  return (
    <div className="max-w-4xl mx-auto space-y-6 pb-20">
      <input
        ref={fileInputRef}
        type="file"
        accept="application/json"
        onChange={handleImportBackup}
        className="hidden"
      />

      <div>
        <h2 className="text-xl font-bold text-slate-100">Configurações Gerais</h2>
        <p className="text-sm text-slate-400">
          Personalize preferências de volume, fade-in, detecção de chamadas/bluetooth e backup.
        </p>
      </div>

      {/* 1. Sound & Volume Card (Section 2.4 & 2.5) */}
      <div className="p-6 rounded-3xl bg-slate-900 border border-slate-800 shadow-md space-y-6">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-xl bg-purple-950 text-purple-400 border border-purple-800/60">
            <Volume2 className="w-5 h-5" />
          </div>
          <h3 className="text-base font-bold text-slate-100">Volume e Fonte de Reprodução</h3>
        </div>

        {/* Master Volume */}
        <div className="space-y-2">
          <div className="flex justify-between items-center text-sm">
            <span className="text-slate-300 font-medium">Volume Geral do App</span>
            <span className="font-bold text-purple-400">{preferences.masterVolumePercent}%</span>
          </div>
          <input
            type="range"
            min={0}
            max={100}
            value={preferences.masterVolumePercent}
            onChange={(e) => onUpdatePreferences({ masterVolumePercent: Number(e.target.value) })}
            className="w-full h-2 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-purple-500"
          />
        </div>

        {/* Section 2.4: Volume Source Selection */}
        <div className="space-y-2 pt-2 border-t border-slate-800/80">
          <label className="block text-xs font-semibold uppercase text-slate-300">
            Fonte do Volume
          </label>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 text-xs">
            <label
              className={`p-3 rounded-2xl border flex items-center gap-2.5 cursor-pointer ${
                preferences.useAppVolumeOnly
                  ? 'bg-purple-950/40 border-purple-500/80 text-purple-200'
                  : 'bg-slate-800/40 border-slate-700 text-slate-400'
              }`}
            >
              <input
                type="radio"
                name="volumeSource"
                checked={preferences.useAppVolumeOnly}
                onChange={() => onUpdatePreferences({ useAppVolumeOnly: true })}
                className="accent-purple-600"
              />
              <div>
                <p className="font-semibold text-slate-200">Usar volume próprio do app</p>
                <p className="text-[11px] text-slate-400">Controla o som internamente sem alterar volume do sistema</p>
              </div>
            </label>

            <label
              className={`p-3 rounded-2xl border flex items-center gap-2.5 cursor-pointer ${
                !preferences.useAppVolumeOnly
                  ? 'bg-purple-950/40 border-purple-500/80 text-purple-200'
                  : 'bg-slate-800/40 border-slate-700 text-slate-400'
              }`}
            >
              <input
                type="radio"
                name="volumeSource"
                checked={!preferences.useAppVolumeOnly}
                onChange={() => onUpdatePreferences({ useAppVolumeOnly: false })}
                className="accent-purple-600"
              />
              <div>
                <p className="font-semibold text-slate-200">Usar volume de mídia do sistema</p>
                <p className="text-[11px] text-slate-400">Acompanha a saída de mídia geral do aparelho</p>
              </div>
            </label>
          </div>
        </div>

        {/* Fade In switch & duration (Section 2.5) */}
        <div className="space-y-3 pt-2 border-t border-slate-800/80">
          <div className="flex items-center justify-between">
            <div>
              <h4 className="text-sm font-semibold text-slate-200">Fade-in (Suavização de Volume)</h4>
              <p className="text-xs text-slate-400">
                Aumenta gradualmente o áudio nos primeiros segundos (padrão: ligado, 2 s).
              </p>
            </div>
            <label className="relative inline-flex items-center cursor-pointer select-none">
              <input
                type="checkbox"
                checked={preferences.fadeInEnabled}
                onChange={(e) => onUpdatePreferences({ fadeInEnabled: e.target.checked })}
                className="sr-only peer"
              />
              <div className="w-11 h-6 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-5 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-600"></div>
            </label>
          </div>

          {preferences.fadeInEnabled && (
            <div className="pl-3 border-l-2 border-purple-500/40 space-y-2 pt-1">
              <div className="flex justify-between text-xs text-slate-300">
                <span>Duração do Fade-in (0,5 s a 10 s):</span>
                <span className="font-semibold text-purple-400">
                  {preferences.fadeInDurationSeconds.toFixed(1)} segundos
                </span>
              </div>
              <input
                type="range"
                min={0.5}
                max={10.0}
                step={0.5}
                value={preferences.fadeInDurationSeconds}
                onChange={(e) => onUpdatePreferences({ fadeInDurationSeconds: Number(e.target.value) })}
                className="w-full h-1.5 bg-slate-800 rounded-lg appearance-none cursor-pointer accent-purple-500"
              />
              <button
                type="button"
                onClick={handleTestFadeIn}
                className="text-xs text-purple-400 hover:text-purple-300 flex items-center gap-1 cursor-pointer pt-1"
              >
                <Play className="w-3 h-3" />
                Testar efeito de fade-in
              </button>
            </div>
          )}
        </div>

        {/* Vibrate switch (Section 2.6) */}
        <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
          <div>
            <h4 className="text-sm font-semibold text-slate-200">Vibrar ao Tocar</h4>
            <p className="text-xs text-slate-400">Emite pulso vibratório junto com o áudio em aparelhos compatíveis.</p>
          </div>
          <label className="relative inline-flex items-center cursor-pointer select-none">
            <input
              type="checkbox"
              checked={preferences.vibrateWithAudio}
              onChange={(e) => onUpdatePreferences({ vibrateWithAudio: e.target.checked })}
              className="sr-only peer"
            />
            <div className="w-11 h-6 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-5 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-600"></div>
          </label>
        </div>

        {/* Notification title switch (Section 2.6) */}
        <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
          <div>
            <h4 className="text-sm font-semibold text-slate-200">Exibir Rótulo do Áudio na Notificação</h4>
            <p className="text-xs text-slate-400">Mostra o título ou frase do áudio disparado no aviso visual.</p>
          </div>
          <label className="relative inline-flex items-center cursor-pointer select-none">
            <input
              type="checkbox"
              checked={preferences.showLabelInNotification}
              onChange={(e) => onUpdatePreferences({ showLabelInNotification: e.target.checked })}
              className="sr-only peer"
            />
            <div className="w-11 h-6 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-5 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-600"></div>
          </label>
        </div>
      </div>

      {/* 2. Skip Rules: Bluetooth, Phone Call & Do Not Disturb (Section 2.3 & 4.6) */}
      <div className="p-6 rounded-3xl bg-slate-900 border border-slate-800 shadow-md space-y-4">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-xl bg-purple-950 text-purple-400 border border-purple-800/60">
            <Headphones className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-slate-100">Regras de Supressão e Disparo</h3>
            <p className="text-xs text-slate-400">Pular áudios automaticamente para não interromper chamadas e fones.</p>
          </div>
        </div>

        {/* Skip Bluetooth */}
        <div className="flex items-center justify-between pt-2">
          <div>
            <h4 className="text-sm font-semibold text-slate-200">Pular se fone Bluetooth conectado</h4>
            <p className="text-xs text-slate-400">
              O áudio é pulado com notificação ("Lembrete ignorado: fone Bluetooth conectado"). Não toca depois.
            </p>
          </div>
          <label className="relative inline-flex items-center cursor-pointer select-none">
            <input
              type="checkbox"
              checked={preferences.skipIfBluetoothConnected}
              onChange={(e) => onUpdatePreferences({ skipIfBluetoothConnected: e.target.checked })}
              className="sr-only peer"
            />
            <div className="w-11 h-6 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-5 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-600"></div>
          </label>
        </div>

        {/* Skip Call */}
        <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
          <div>
            <h4 className="text-sm font-semibold text-slate-200">Pular se em chamada telefônica</h4>
            <p className="text-xs text-slate-400">
              Ignora o lembrete enquanto uma ligação estiver em andamento.
            </p>
          </div>
          <label className="relative inline-flex items-center cursor-pointer select-none">
            <input
              type="checkbox"
              checked={preferences.skipIfCallInProgress}
              onChange={(e) => onUpdatePreferences({ skipIfCallInProgress: e.target.checked })}
              className="sr-only peer"
            />
            <div className="w-11 h-6 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-5 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-600"></div>
          </label>
        </div>

        {/* Respect Do Not Disturb */}
        <div className="flex items-center justify-between pt-2 border-t border-slate-800/80">
          <div>
            <h4 className="text-sm font-semibold text-slate-200">Respeitar Não Perturbe (Padrão: Ligado)</h4>
            <p className="text-xs text-slate-400">
              Pula os lembretes discretamente se o modo Não Perturbe estiver ativado.
            </p>
          </div>
          <label className="relative inline-flex items-center cursor-pointer select-none">
            <input
              type="checkbox"
              checked={preferences.respectDoNotDisturb}
              onChange={(e) => onUpdatePreferences({ respectDoNotDisturb: e.target.checked })}
              className="sr-only peer"
            />
            <div className="w-11 h-6 bg-slate-800 peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-5 peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-all peer-checked:bg-purple-600"></div>
          </label>
        </div>
      </div>

      {/* 3. Default Resume Policy (Section 2.3) */}
      <div className="p-6 rounded-3xl bg-slate-900 border border-slate-800 shadow-md space-y-4">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-xl bg-purple-950 text-purple-400 border border-purple-800/60">
            <Clock className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-slate-100">Regra de Retomada pós-Descanso</h3>
            <p className="text-xs text-slate-400">Comportamento padrão para perfis ao término de descansos.</p>
          </div>
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-3 text-xs">
          <label
            className={`p-3.5 rounded-2xl border flex items-center gap-2.5 cursor-pointer ${
              preferences.defaultResumePolicy === 'NEXT_GRID_MARK'
                ? 'bg-purple-950/40 border-purple-500/80 text-purple-200'
                : 'bg-slate-800/40 border-slate-700 text-slate-400'
            }`}
          >
            <input
              type="radio"
              name="defaultResumePolicy"
              checked={preferences.defaultResumePolicy === 'NEXT_GRID_MARK'}
              onChange={() => onUpdatePreferences({ defaultResumePolicy: 'NEXT_GRID_MARK' })}
              className="accent-purple-600"
            />
            <div>
              <p className="font-semibold text-slate-200">Retomar na próxima marca da grade (Padrão)</p>
              <p className="text-[11px] text-slate-400">Aguarda o próximo múltiplo exato do intervalo</p>
            </div>
          </label>

          <label
            className={`p-3.5 rounded-2xl border flex items-center gap-2.5 cursor-pointer ${
              preferences.defaultResumePolicy === 'AT_REST_END'
                ? 'bg-purple-950/40 border-purple-500/80 text-purple-200'
                : 'bg-slate-800/40 border-slate-700 text-slate-400'
            }`}
          >
            <input
              type="radio"
              name="defaultResumePolicy"
              checked={preferences.defaultResumePolicy === 'AT_REST_END'}
              onChange={() => onUpdatePreferences({ defaultResumePolicy: 'AT_REST_END' })}
              className="accent-purple-600"
            />
            <div>
              <p className="font-semibold text-slate-200">Retomar ao fim do descanso</p>
              <p className="text-[11px] text-slate-400">Toca exatamente no minuto em que o descanso termina</p>
            </div>
          </label>
        </div>
      </div>

      {/* 4. Battery Assistant & Background Execution (Section 4.5) */}
      <div className="p-6 rounded-3xl bg-slate-900 border border-slate-800 shadow-md space-y-4">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="p-2 rounded-xl bg-indigo-950 text-indigo-400 border border-indigo-800/60">
              <Laptop className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-base font-bold text-slate-100">Assistente de Bateria e Execução</h3>
              <p className="text-xs text-slate-400">Recomendações para que seus áudios toquem pontualmente.</p>
            </div>
          </div>

          <button
            onClick={() => setShowBatteryAssistant(!showBatteryAssistant)}
            className="px-3.5 py-1.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold cursor-pointer border border-slate-700"
          >
            {showBatteryAssistant ? 'Recolher' : 'Ver Orientações'}
          </button>
        </div>

        {showBatteryAssistant && (
          <div className="p-4 rounded-2xl bg-slate-800/50 border border-slate-700/60 space-y-3 text-xs text-slate-300 animate-in fade-in">
            <h4 className="font-bold text-slate-100 flex items-center gap-2">
              <BatteryWarning className="w-4 h-4 text-amber-400" />
              Orientações por Fabricante e Navegador:
            </h4>
            <div className="space-y-2 text-slate-400 leading-relaxed">
              <p>
                <strong className="text-slate-200">Xiaomi / MIUI / HyperOS:</strong> Acesse Configurações &gt; Apps &gt; Permissões &gt; Início Automático e ative. Na economia de bateria, selecione "Sem restrições".
              </p>
              <p>
                <strong className="text-slate-200">Samsung (One UI):</strong> Em Configurações &gt; Assistência do aparelho &gt; Bateria, certifique-se de não colocar o aplicativo na lista de "Apps suspensos".
              </p>
              <p>
                <strong className="text-slate-200">Navegador Web / Desktop:</strong> Mantenha esta aba aberta ou fixada. Em celulares, adicione o app à tela inicial como PWA para garantir prioridade de execução.
              </p>
            </div>
          </div>
        )}
      </div>

      {/* 5. Backup & Restore (Section 2.8) */}
      <div className="p-6 rounded-3xl bg-slate-900 border border-slate-800 shadow-md space-y-4">
        <div className="flex items-center gap-2.5">
          <div className="p-2 rounded-xl bg-purple-950 text-purple-400 border border-purple-800/60">
            <Download className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-base font-bold text-slate-100">Backup e Exportação</h3>
            <p className="text-xs text-slate-400">
              Exporte todos os perfis, descansos, rótulos, pesos e volumes para um arquivo JSON.
            </p>
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-3 pt-2">
          <button
            onClick={handleExportBackup}
            className="px-4 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold flex items-center gap-2 transition-colors border border-slate-700 cursor-pointer"
          >
            <Download className="w-4 h-4 text-purple-400" />
            Exportar Backup (JSON)
          </button>

          <button
            onClick={() => fileInputRef.current?.click()}
            className="px-4 py-2.5 rounded-xl bg-slate-800 hover:bg-slate-700 text-slate-200 text-xs font-semibold flex items-center gap-2 transition-colors border border-slate-700 cursor-pointer"
          >
            <Upload className="w-4 h-4 text-indigo-400" />
            Importar Backup (JSON)
          </button>

          <button
            onClick={handleResetData}
            className="px-4 py-2.5 rounded-xl bg-rose-950/40 hover:bg-rose-900/40 text-rose-300 text-xs font-semibold flex items-center gap-2 transition-colors border border-rose-800/60 cursor-pointer ml-auto"
          >
            <RotateCcw className="w-4 h-4" />
            Redefinir Dados
          </button>
        </div>
      </div>
    </div>
  );
};
