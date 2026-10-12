# Lembretes em Áudio

Aplicativo Android nativo (Kotlin + Jetpack Compose + Material 3) para reprodução periódica de áudios e afirmações selecionadas pelo usuário em intervalos fixos, respeitando múltiplos períodos de descanso e rotinas personalizadas por dia da semana.

---

## Como Compilar

1. **Requisitos:**
   - Android Studio Hedgehog ou superior (AGP 8.x / 9.x)
   - JDK 17 ou 21
   - Android SDK com suporte a `minSdk 26` e `targetSdk 36`

2. **Compilação e Execução via Gradle:**
   ```bash
   gradle assembleDebug
   ```
   Para rodar a suíte completa de testes unitários:
   ```bash
   gradle :app:testDebugUnitTest
   ```

---

## Permissões e Configurações Recomendadas

1. **Biblioteca de Áudios:**
   - O aplicativo não possui áudios embutidos. Na primeira abertura, vá para a aba **Biblioteca** e toque em **"Adicionar áudios"** para escolher seus arquivos (MP3, WAV, OGG, M4A) através do seletor do sistema (Storage Access Framework).

2. **Notificações:**
   - Permissão concedida via popup no primeiro início (`POST_NOTIFICATIONS`) para exibir o status do serviço em segundo plano e avisos de lembretes.

3. **Alarmes Exatos:**
   - No Android 12+, certifique-se de permitir o agendamento de alarmes exatos em **Configurações > Apps > Lembretes em Áudio > Alarmes e lembretes**.

4. **Otimização de Bateria (Modo Doze):**
   - Acesse a aba **Configurações > Assistente de Bateria** e toque em **"Desativar otimização"** para garantir que o aparelho toque seus áudios com a tela bloqueada e em repouso prolongado. Em aparelhos Xiaomi (MIUI/HyperOS), ative também o "Início Automático".
