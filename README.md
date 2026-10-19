# Lembretes em Áudio (Android)

*Read this in other languages: [English](README-en.md)*

---

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
   - **Texto para Fala (TTS):** Síntese de voz nativa do Android (Text-to-Speech); vozes e idiomas disponíveis dependem da configuração do dispositivo.
   - Controle individual de volume (0 a 100%) e peso no sorteio para cada áudio.

3. **Arquitetura e Robustez em Segundo Plano:**
   - **Room Database:** Persistência local de áudios, perfis, períodos de descanso e lembretes gravados.
   - **WorkManager & Exact Alarms:** Agendamentos via `AlarmManager` e `CoroutineWorker`.
   - **Foreground Service:** Serviço em primeiro plano com tipo `mediaPlayback` para confiabilidade em tela bloqueada.
   - **Tratamento de Estado do Dispositivo:** Tratamento de chamadas, modo Não Perturbe (DND) e dispositivos Bluetooth.
   - **Assistente de Otimização de Bateria:** Verificação e atalhos para configurações de bateria do sistema.

4. **Backup e Portabilidade:**
   - Exportação/importação de preferências, perfis, períodos de descanso, metadados dos áudios e lembretes em JSON.
   - **Limitação atual:** o JSON não inclui os bytes dos arquivos de áudio locais. Após importar em outro dispositivo, os arquivos precisam ser copiados/importados novamente; referências a arquivos ausentes são desativadas para evitar falhas de reprodução.

---

## Idiomas da Interface

A interface oferece seleção de idioma entre **português do Brasil (PT-BR), inglês e espanhol**. A escolha é armazenada localmente nas preferências do aplicativo.

---

## Arquitetura do Projeto

- **Linguagem:** Kotlin
- **UI Toolkit:** Jetpack Compose (Material Design 3)
- **Banco de Dados:** Room Database
- **Preferências:** Jetpack DataStore Preferences
- **Segundo Plano:** WorkManager, Foreground Service, BroadcastReceivers
- **Áudio:** Android MediaPlayer, AudioFocusManager, TextToSpeech


## 👤 Autoria e desenvolvimento

Lembretes em Áudio é um aplicativo Android nativo, desenvolvido de forma independente por Pablo Phillipe Cândido dos Santos, para agendar e reproduzir lembretes sonoros, gravações de voz e mensagens de texto para fala em rotinas configuráveis.

O desenvolvimento contou com ferramentas de inteligência artificial generativa como recurso auxiliar, mantendo-se sob responsabilidade do autor a concepção, implementação, integração e verificação do projeto.

Currículo Lattes: [http://lattes.cnpq.br/9500873674712528](http://lattes.cnpq.br/9500873674712528)
