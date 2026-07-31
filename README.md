# Smartbalanço — aplicativo Android

Casca Android (Capacitor) do PWA que vive em
<https://smartbalanco.github.io/smartbalanco/> (repo `smartbalanco/smartbalanco`).

## O que este projeto é — e o que não é

O app **não carrega uma cópia** das telas: ele abre o site, definido em
`server.url` no `capacitor.config.json`. Consequência prática:

- Mudou tela, texto, cor, regra de negócio? **Não precisa de APK novo.** Basta
  publicar o site; o app pega na próxima abertura.
- Só precisa gerar APK novo quando muda algo **nativo**: plugin, permissão,
  ícone, nome ou a própria URL.

O que o APK acrescenta ao PWA é o que o navegador não dá: **notificações
locais** dos vencimentos, agendadas no próprio aparelho.

## Entrar no app

O login do Google **não funciona dentro do WebView** — o próprio Google bloqueia
esse fluxo por segurança. Por isso a entrada aqui é por **código de acesso**:

1. Abra o site no navegador do computador ou do celular e faça login normal.
2. Vá em ⚙ **Configurações → Acesso pelo aplicativo** e copie o código.
3. No app, toque em **Entrar com código de acesso** e cole.

A sessão dura 30 dias e se renova a cada uso, então na prática você faz isso
uma vez só.

## Build

O APK é compilado pelo **GitHub Actions** (`.github/workflows/build-apk.yml`),
não localmente: o Gradle não roda no ambiente do assistente. Todo push que
toque em `android/`, `capacitor.config.json` ou `package.json` dispara o build,
e o APK sai no Releases.

### Pré-requisito, uma vez só

Criar o Secret **`DEBUG_KEYSTORE_B64`** no repositório
(*Settings → Secrets and variables → Actions*), com o conteúdo de
`android/app/debug.keystore` em base64.

A keystore é gitignorada de propósito. Sem esse segredo, cada build sairia
assinado com uma chave diferente, e o Android recusa instalar por cima de um
app assinado com outra chave — só desinstalando antes.

### Build local (opcional, mais rápido)

```bash
npx cap sync android
cd android && ./gradlew assembleDebug
```

Precisa de `JAVA_HOME` apontando para o JBR do Android Studio.

## Notificações

Usa `@capacitor/local-notifications`: nada de Firebase, servidor de push ou
custo. O app agenda no aparelho um aviso às 9h da véspera de cada conta a
vencer, e reagenda toda vez que o dashboard carrega. O código está no
`app.js` do repo do site (`agendarNotificacoesContas`), e é ignorado em
silêncio quando roda no navegador.

## Widget

Ainda não existe. Widget de tela inicial exige código nativo (Kotlin + layout
XML) e um armazenamento compartilhado que o app preenche e o widget lê —
é um projeto à parte, não sai como efeito colateral do APK.
