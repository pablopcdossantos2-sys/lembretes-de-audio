# Lembretes em Áudio (Android)

Aplicativo Android nativo moderno desenvolvido em **Kotlin** e **Jetpack Compose** (Material Design 3) para reprodução periódica de áudios, gravações de voz e lembretes sonoros em intervalos configuráveis, respeitando múltiplos períodos de descanso, horários silenciosos, rotinas por dia da semana e o estado do dispositivo.

---

## Funcionalidades Principais

1. **Perfis de Funcionamento e Agendamento Flexível:**
   - Janelas de operação personalizadas com suporte a horários que cruzam a meia-noite.
   - Intervalos de repetição de 5 a 120 minutos.
   - Seleção de dias da semana (Segunda a Domingo) com detecção e aviso de conflitos de prioridade.
   - **Múltiplos Períodos de Descanso (*Resting Periods*):** Silenciamento automático durante horários de repouso (ex: Almoço 12:00 às 13:00, Pausa para café 16:00 às 16:30).
   - Políticas de retomada pós-descanso configuráveis (`NEXT_GRID_MARK`, `AT_REST_END`).
   - Modos de reprodução: Sequencial, Aleatório Ponderado por pesos (1 a 5) ou Áudio específico.
   - Suavização de volume (*Fade-in*) global ou personalizada por perfil (0.5s a 10s).

2. **Biblioteca de Áudios Completa:**
   - **Gravação de Áudio com Microfone:** Gravador integrado com visualizador de onda em tempo real, temporizador, pause/resume e reprodução de prévia.
   - **Importação de Arquivos:** Suporte a arquivos locais (MP3, WAV, M4A, OGG) via seletor de arquivos do sistema.
   - **Texto para Fala (TTS):** Síntese de voz nativa do Android (Text-to-Speech) em Português do Brasil.
   - Controle individual de volume (0 a 100%) e peso no sorteio para cada áudio.

3. **Arquitetura e Robustez em Segundo Plano:**
   - **Room Database:** Persistência local segura de áudios, perfis, períodos de descanso e lembretes gravados.
   - **WorkManager & Exact Alarms:** Agendamentos de alta precisão via `AlarmManager` e `CoroutineWorker`.
   - **Foreground Service:** Serviço de execução em primeiro plano com tipo `mediaPlayback` para confiabilidade em tela bloqueada.
   - **Tratamento de Estado do Dispositivo:** Respeita chamadas telefônicas em andamento, modo Não Perturbe (DND) e fones Bluetooth.
   - **Assistente de Otimização de Bateria:** Verificação e atalhos para desativar otimizações de bateria agressivas do sistema.

4. **Backup e Portabilidade:**
   - Exportação e importação completa de dados e configurações em formato JSON.

---

## Arquitetura do Projeto

- **Linguagem:** Kotlin 2.2
- **UI Toolkit:** Jetpack Compose (Material Design 3)
- **Banco de Dados:** Room Database
- **Preferências:** Jetpack DataStore Preferences
- **Segundo Plano:** WorkManager, Foreground Service, BroadcastReceivers
- **Áudio:** Android MediaPlayer, AudioFocusManager, TextToSpeech
