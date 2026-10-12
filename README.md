# Lembretes em Áudio

Aplicativo React moderno (TypeScript + Vite + Tailwind CSS) para reprodução periódica de áudios, gravações de voz e afirmações selecionadas pelo usuário em intervalos fixos, respeitando múltiplos períodos de descanso e rotinas personalizadas por dia da semana.

---

## Funcionalidades Principais

1. **Perfis de Funcionamento Personalizados:**
   - Janelas de operação personalizadas (com suporte a horários que cruzam a meia-noite).
   - Intervalos de repetição flexíveis (5 a 120 minutos).
   - Dias da semana configuráveis (Segunda a Domingo).
   - Múltiplos períodos de descanso (ex.: Almoço, Sono) com mesclagem automática de intervalos.
   - Políticas de retomada pós-descanso: *Próxima marca da grade* (`NEXT_GRID_MARK`) ou *Imediato no fim do descanso* (`AT_REST_END`).
   - Modos de reprodução: Sequencial, Aleatório com pesos ponderados (1 a 5 estrelas) e Áudio fixo específico.
   - Suavização de volume (*Fade-in*) global ou personalizada por perfil (0.5s a 10s).

2. **Biblioteca de Áudios Versátil:**
   - **Upload de Arquivos de Áudio:** Suporte a arquivos locais (MP3, WAV, OGG, M4A, FLAC, WEBM) com armazenamento em IndexedDB.
   - **Gravador de Voz Embutido:** Gravação direta pelo microfone com visualizador de onda em tempo real (Waveform Canvas), temporizador e controle de reprodução antes de salvar.
   - **Texto para Fala (TTS):** Geração de fala sintetizada via Web Speech API com teste prévio imediato.
   - Controle individual de volume (0 a 100%) e peso no sorteio para cada item da biblioteca.

3. **Agendamento e Monitoramento:**
   - Cálculo e previsão dos próximos 5 disparos na tela inicial.
   - Controle de silêncio rápido (*Snooze* de 30 min, 1h, 2h ou retomada manual).
   - Suporte a notificações visuais e sonoras em navegadores web.
   - Botão para teste de disparo imediato.

4. **Backup e Portabilidade:**
   - Exportação e importação completa de dados, áudios e configurações em formato JSON.

---

## Como Executar

1. **Instalação de Dependências:**
   ```bash
   npm install
   ```

2. **Servidor de Desenvolvimento:**
   ```bash
   npm run dev
   ```
   O app estará disponível em `http://localhost:3000`.

3. **Compilação de Produção:**
   ```bash
   npm run build
   ```
