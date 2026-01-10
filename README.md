# Parking Auto Renewer (Android, Kotlin)

App robusto que automatiza renovações de estacionamento com suporte a sessões persistentes e interface resiliente.

**Versão:** v1.0.5 (versionCode 6)

## O que está implementado

### Funcionalidades Principais
- **Auto-Renew Session:** Interface completa com renovação automática de estacionamento
- **Rotação de Tela Suportada:** UI mantém estado completo ao girar o dispositivo
- **Sessão Persistente:** Mantém ativa mesmo quando app é minimizado, clicado no ícone ou notificação
- **Service Immortal:** Serviço de background com 3 camadas de proteção:
  - `PARTIAL_WAKE_LOCK` mantém CPU ativa
  - `START_REDELIVER_INTENT` recriam Service se morto
  - `stopWithTask=false` continua mesmo se app removida do recents
- **UI Recovery:** Restaura estado completo (contadores, confirmações, countdown) após crash/kill
- **Countdown Preciso:** Mantém tempo exato até próxima renovação mesmo ao voltar do minimizado
- **Bloqueio de Back Button:** Impede saída involuntária durante sessão ativa
- **Versão Discreta:** Display discreto em cor #666666 como Debug Mode
- **Exit Completo:** Botão EXIT para parar serviço e matar app completamente

### Permissões Requeridas
- `INTERNET` - Para acesso às páginas de renovação
- `WAKE_LOCK` - Para manter Service vivo
- `POST_NOTIFICATIONS` - Para notificações de status
- `SCHEDULE_EXACT_ALARM` - Para agendar renovações precisas
- `FOREGROUND_SERVICE` - Para serviço em foreground

## Arquivos Principais

- [AutoRenewActivity.kt](app/src/main/java/com/example/parkingautorenew/AutoRenewActivity.kt): Controller principal com UI recovery
- [ParkingRenewalService.kt](app/src/main/java/com/example/parkingautorenew/ParkingRenewalService.kt): Service immortal com WakeLock
- [MainActivity.kt](app/src/main/java/com/example/parkingautorenew/MainActivity.kt): Launcher com botão Exit
- [activity_auto_renew.xml](app/src/main/res/layout/activity_auto_renew.xml): Layout principal
- [activity_main.xml](app/src/main/res/layout/activity_main.xml): Tela inicial com Auto Renew e Exit
- [AndroidManifest.xml](app/src/main/AndroidManifest.xml): Configuração com stopWithTask="false"

## Build e Deployment

### macOS
```bash
cd /Users/humferre/localcode/autorenew
./gradlew :app:assembleDebug --warning-mode all
```

### Windows
```powershell
cd C:\Users\Admin\StudioProjects\parkingautorenew
gradlew :app:assembleDebug --warning-mode all
```

### Instalar APK
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Fluxo de Uso

1. **Iniciar App:** MainActivity com botões AUTO RENEW e EXIT
2. **Clicar AUTO RENEW:** Abre AutoRenewActivity
3. **Preencher Dados:** Placa, duração, frequência, email (opcional)
4. **Iniciar Sessão:** Clica START
   - Service inicia com WakeLock
   - Renovação automática começa em background
   - Countdown mostra tempo até próxima renovação
5. **Minimizar:** Clica Home/sai do app
   - Service CONTINUA rodando
   - Notificação permanece visível
   - Renovações continuam normalmente
6. **Voltar:** Clica ícone do app OU notificação
   - UI restaurada com estado exato (contadores, countdown, confirmações)
   - Countdown mantém tempo preciso (não reseta)
   - Sessão continua onde parou
7. **Parar Sessão:** Clica STOP
   - Para renovações automáticas
   - Mostra último status
8. **Sair Completamente:** Clica EXIT (em qualquer tela)
   - Mata o serviço de background
   - Encerra o processo completamente

## Proteções e Garantias

### Session Persistence
- ✅ Minimize → Click Icon = Session intacta, countdown correto
- ✅ Minimize → Click Notification = Session intacta, countdown correto
- ✅ Remove app from recents = Service continua rodando
- ✅ Activity crash = Service continua, UI se recupera ao voltar
- ✅ Rotação de tela = UI mantém estado completo, countdown preciso

### Back Button Protection
- ✅ Durante sessão ativa: back button bloqueado silenciosamente
- ✅ Sem sessão ativa: back button funciona normalmente
- ✅ Sem seta de voltar na Action Bar durante sessão

### Data Persistence
- ✅ Contadores salvos (sucesso/falha)
- ✅ Confirmação anterior salva
- ✅ Próximo tempo de renovação exato
- ✅ Configurações do usuário preservadas

## Problemas Comuns e Solução

### Build Errors
- **Gradle sync fails:** Limpe `.gradle` e `build`, reinicie Android Studio
- **JDK version:** Use JDK 17 ou superior
- **Plugin incompatibility:** Verifique `gradle/libs.versions.toml`

### Runtime Issues
- **Service não inicia:** Verifique permissão `WAKE_LOCK` e `FOREGROUND_SERVICE`
- **Notificação não aparece:** Verifique permissão `POST_NOTIFICATIONS` e canal criado
- **Countdown reseta:** Usar `updateCountdown()` em vez de `startCountdownTimer()` no recovery
- **App minimizado = fim da sessão:** Implementar WakeLock e START_REDELIVER_INTENT

## Roadmap Futuro
- [ ] Integração com sistema de bateria otimizado
- [ ] JobScheduler como backup da alarma
- [ ] Whitelist para otimização de bateria
- [ ] Suporte a múltiplas placas simultâneas
