package com.smartbalanco.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lê as notificações de compra dos aplicativos de banco e guarda numa fila
 * local, para o app transformar em lançamento depois.
 *
 * O Android só entrega notificações a um serviço com permissão especial, que o
 * usuário concede à mão em Configurações. Essa permissão vale para TODAS as
 * notificações do aparelho — por isso este serviço:
 *
 *   1. só olha os pacotes da lista PACOTES (bancos), descartando o resto no
 *      primeiro if, antes de ler qualquer conteúdo;
 *   2. só guarda o que tem valor em reais, o que já elimina propaganda e
 *      aviso de login;
 *   3. grava apenas no armazenamento privado do próprio app;
 *   4. NÃO envia nada para lugar nenhum. Quem envia é o app, e o destino é a
 *      aba de Aprovações, onde você confere antes de virar lançamento.
 *
 * Fragilidade conhecida: se o banco mudar o texto da notificação, a extração
 * do valor ou do estabelecimento pode falhar. Por isso o texto ORIGINAL é
 * guardado junto — dá para corrigir a leitura sem perder a compra.
 */
public class LeitorNotificacoes extends NotificationListenerService {

    private static final String TAG = "SmartbalancoNotif";
    public static final String PREFS = "smartbalanco_notificacoes";
    public static final String CHAVE_FILA = "fila";

    /** Guarda no máximo isto: a fila é um rascunho, não um histórico. */
    private static final int LIMITE_FILA = 60;

    private static final String CANAL = "compras_capturadas";
    private static final int ID_AVISO = 90210;

    /**
     * De quanto em quanto tempo a barra de notificação pode ser incomodada.
     *
     * A LEITURA não é afetada por isto: o Android entrega a notificação do
     * banco uma única vez, no instante em que ela chega, e não a guarda para
     * ser buscada depois — adiar a leitura seria perder a compra. O que espera
     * é só o aviso, que junta o período num resumo só.
     */
    private static final long JANELA_AVISO_MS = 60 * 60 * 1000L;   // 1 hora

    private static final String CHAVE_ULTIMO_AVISO = "ultimoAviso";
    private static final String CHAVE_PEND_QTD     = "pendentesQtd";
    private static final String CHAVE_PEND_SOMA    = "pendentesSoma";
    private static final String CHAVE_PEND_ONDE    = "pendentesOnde";
    private static final String CHAVE_PEND_BANCO   = "pendentesBanco";

    /** Evita empilhar agendamentos: um basta para a janela inteira. */
    private boolean avisoAgendado = false;
    private final Handler relogio = new Handler(Looper.getMainLooper());

    /**
     * Pacotes observados. Qualquer outro é ignorado sem ser lido.
     * Nubank e Itaú entram porque o custo é zero e a lista é fácil de ampliar.
     */
    private static final String[] PACOTES = {
        "com.xp.investimentos",      // XP
        "br.com.xpi",                // XP (variação)
        "br.com.intermedium",        // Inter
        "com.mercadopago.wallet",    // Mercado Pago
        "com.nu.production",         // Nubank
        "com.itau"                   // Itaú
    };

    /** "R$ 1.234,56" ou "R$ 12,90" — com ou sem espaço depois do R$. */
    private static final Pattern VALOR =
        Pattern.compile("R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2})");

    /**
     * O estabelecimento costuma vir depois de "em" ou "no/na":
     *   "Compra aprovada de R$ 32,90 em PADARIA CENTRAL"
     */
    private static final Pattern ESTABELECIMENTO =
        Pattern.compile("(?:\\bem|\\bno|\\bna)\\s+([A-Z0-9][^.,;\\n]{2,40})");

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            final String pacote = sbn.getPackageName();
            if (!ehPacoteObservado(pacote)) return;   // descarta antes de ler

            Bundle extras = sbn.getNotification().extras;
            String titulo = textoDe(extras, "android.title");
            String corpo  = textoDe(extras, "android.text");
            if (corpo.isEmpty()) corpo = textoDe(extras, "android.bigText");

            String completo = (titulo + " " + corpo).trim();
            if (completo.isEmpty()) return;

            // Sem valor em reais não é compra: corta propaganda, aviso de
            // login, "sua fatura fechou" e afins.
            Matcher mv = VALOR.matcher(completo);
            if (!mv.find()) return;

            String valorTexto = mv.group(1);

            String estabelecimento = "";
            Matcher me = ESTABELECIMENTO.matcher(completo);
            if (me.find()) estabelecimento = me.group(1).trim();

            guardar(pacote, titulo, corpo, valorTexto, estabelecimento,
                    sbn.getPostTime());

        } catch (Exception e) {
            // Um erro aqui não pode derrubar o serviço: ele perderia as
            // próximas notificações até o Android reiniciá-lo.
            Log.w(TAG, "Falha ao ler notificação: " + e.getMessage());
        }
    }

    private boolean ehPacoteObservado(String pacote) {
        if (pacote == null) return false;
        for (String p : PACOTES) {
            if (pacote.startsWith(p)) return true;
        }
        return false;
    }

    private String textoDe(Bundle extras, String chave) {
        CharSequence cs = extras.getCharSequence(chave);
        return cs == null ? "" : cs.toString().trim();
    }

    private void guardar(String pacote, String titulo, String corpo,
                         String valor, String estabelecimento, long quando) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray fila = new JSONArray(prefs.getString(CHAVE_FILA, "[]"));

            // O mesmo aviso costuma ser postado mais de uma vez (atualização da
            // notificação). Repetir a compra na fila viraria lançamento dobrado.
            String assinatura = pacote + "|" + valor + "|" + corpo;
            for (int i = 0; i < fila.length(); i++) {
                if (assinatura.equals(fila.getJSONObject(i).optString("assinatura"))) return;
            }

            JSONObject item = new JSONObject();
            item.put("assinatura", assinatura);
            item.put("app", nomeDoBanco(pacote));
            item.put("pacote", pacote);
            item.put("titulo", titulo);
            item.put("texto", corpo);          // original, para corrigir a leitura
            item.put("valor", valor);
            item.put("estabelecimento", estabelecimento);
            item.put("quando", quando);

            fila.put(item);

            // Descarta o começo se passar do limite.
            while (fila.length() > LIMITE_FILA) fila.remove(0);

            prefs.edit().putString(CHAVE_FILA, fila.toString()).apply();
            Log.i(TAG, "Compra capturada: " + valor + " (" + nomeDoBanco(pacote) + ")");

            acumularParaOAviso(prefs, estabelecimento, valor, nomeDoBanco(pacote));
            talvezAvisar(fila.length());

        } catch (Exception e) {
            Log.w(TAG, "Falha ao guardar: " + e.getMessage());
        }
    }

    /**
     * Guarda a compra no acumulado do período, para o aviso da hora cheia.
     *
     * Os contadores ficam em SharedPreferences, não em memória: se o Android
     * matar o serviço no meio da hora (o que ele faz quando quer), o que já
     * foi capturado continua contando no próximo aviso.
     */
    private void acumularParaOAviso(SharedPreferences prefs, String estabelecimento,
                                    String valor, String banco) {
        int qtd = prefs.getInt(CHAVE_PEND_QTD, 0) + 1;
        double soma = Double.longBitsToDouble(
            prefs.getLong(CHAVE_PEND_SOMA, Double.doubleToLongBits(0))) + emNumero(valor);

        prefs.edit()
            .putInt(CHAVE_PEND_QTD, qtd)
            .putLong(CHAVE_PEND_SOMA, Double.doubleToLongBits(soma))
            .putString(CHAVE_PEND_ONDE,
                (estabelecimento == null || estabelecimento.isEmpty()) ? banco : estabelecimento)
            .putString(CHAVE_PEND_BANCO, banco)
            .apply();
    }

    /**
     * Avisa agora se a janela já venceu; senão, agenda para quando vencer.
     *
     * A primeira compra depois de uma hora de silêncio avisa NA HORA — é o que
     * te diz que a captura está viva. As que vierem em seguida entram no
     * mesmo resumo, em vez de virarem um aviso cada.
     *
     * O agendamento é um Handler simples, e ele morre junto com o serviço. Por
     * isso a decisão também é refeita a cada compra nova: se o aviso pendente
     * se perdeu, a próxima captura o dispara. O que nunca se perde é a compra,
     * que já está gravada na fila antes disto rodar.
     */
    private void talvezAvisar(final int naFila) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long agora = System.currentTimeMillis();
        long ultimo = prefs.getLong(CHAVE_ULTIMO_AVISO, 0);
        long falta = JANELA_AVISO_MS - (agora - ultimo);

        if (falta <= 0) { avisarResumo(naFila); return; }

        if (avisoAgendado) return;
        avisoAgendado = true;
        relogio.postDelayed(new Runnable() {
            @Override public void run() {
                avisoAgendado = false;
                avisarResumo(filaAtual());
            }
        }, falta);
    }

    /** Quantas compras esperam envio agora (o número muda enquanto se espera). */
    private int filaAtual() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString(CHAVE_FILA, "[]")).length();
        } catch (Exception e) { return 0; }
    }

    /** "1.234,56" -> 1234.56. Valor ilegível vira 0 e não estraga a soma. */
    private double emNumero(String valor) {
        try {
            return Double.parseDouble(valor.replace(".", "").replace(",", "."));
        } catch (Exception e) { return 0; }
    }

    /** 1234.56 -> "1.234,56", que é como o valor aparece no resto do app. */
    private String emReais(double v) {
        return String.format(java.util.Locale.forLanguageTag("pt-BR"), "%,.2f", v);
    }

    /**
     * Monta o resumo do período e zera o acumulado.
     *
     * Uma compra só continua mostrando o valor e o lugar, como antes — juntar
     * não pode piorar o caso comum.
     */
    private void avisarResumo(int naFila) {
        SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int qtd = prefs.getInt(CHAVE_PEND_QTD, 0);
        if (qtd <= 0) return;

        double soma = Double.longBitsToDouble(
            prefs.getLong(CHAVE_PEND_SOMA, Double.doubleToLongBits(0)));
        String onde  = prefs.getString(CHAVE_PEND_ONDE, "");
        String banco = prefs.getString(CHAVE_PEND_BANCO, "");

        String titulo, corpo;
        if (qtd == 1) {
            titulo = "Anotei: R$ " + emReais(soma);
            corpo  = onde + " · " + banco;
        } else {
            titulo = "Anotei " + qtd + " compras · R$ " + emReais(soma);
            corpo  = "Última: " + onde + " · " + banco;
        }

        avisarQueAnotou(titulo, corpo, naFila);

        prefs.edit()
            .putLong(CHAVE_ULTIMO_AVISO, System.currentTimeMillis())
            .putInt(CHAVE_PEND_QTD, 0)
            .putLong(CHAVE_PEND_SOMA, Double.doubleToLongBits(0))
            .apply();
    }

    /**
     * Põe o resumo na barra. Serve para dois fins: você sabe que a captura
     * está funcionando, e vê o que foi lido — se um valor saiu errado, dá para
     * perceber ali, não só dias depois na conferência.
     */
    private void avisarQueAnotou(String titulo, String corpo, int naFila) {
        try {
            NotificationManager nm =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                NotificationChannel canal = new NotificationChannel(
                    CANAL, "Compras anotadas", NotificationManager.IMPORTANCE_LOW);
                canal.setDescription("Aviso de compra capturada do banco");
                // IMPORTANCE_LOW: aparece sem som nem vibração. É uma
                // confirmação, não um alerta. Com a janela de uma hora, o teto
                // é de 24 avisos por dia, e na prática dá uns cinco.
                nm.createNotificationChannel(canal);
            }

            Intent abrir = new Intent(this, MainActivity.class);
            abrir.setAction(Intent.ACTION_VIEW);
            abrir.setData(Uri.parse("com.smartbalanco.app://capturadas"));
            abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent aoTocar = PendingIntent.getActivity(
                this, 0, abrir,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification n = new Notification.Builder(this, CANAL)
                .setSmallIcon(android.R.drawable.ic_menu_save)
                .setContentTitle(titulo)
                .setContentText(corpo)
                .setSubText(naFila + " aguardando envio")
                .setContentIntent(aoTocar)
                .setAutoCancel(true)
                .build();

            // Id fixo: a notificação nova SUBSTITUI a anterior em vez de
            // empilhar. Cinco compras num dia não podem virar cinco avisos
            // parados na barra.
            nm.notify(ID_AVISO, n);

        } catch (Exception e) {
            Log.w(TAG, "Não consegui avisar: " + e.getMessage());
        }
    }

    /**
     * Zera o acumulado do aviso e tira o resumo da barra.
     *
     * Chamado quando o app manda a fila para Aprovações: dali em diante o
     * "3 aguardando envio" seria mentira, e o resumo da hora cheia anunciaria
     * compras que você já conferiu.
     *
     * O relógio da janela (CHAVE_ULTIMO_AVISO) NÃO é zerado de propósito: abrir
     * o app não deve liberar um aviso novo em seguida.
     */
    public static void esquecerPendencias(Context ctx) {
        try {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putInt(CHAVE_PEND_QTD, 0)
                .putLong(CHAVE_PEND_SOMA, Double.doubleToLongBits(0))
                .apply();

            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(ID_AVISO);
        } catch (Exception e) {
            Log.w(TAG, "Falha ao limpar pendências: " + e.getMessage());
        }
    }

    /** Nome legível para a tela — o pacote não diz nada a quem lê. */
    private String nomeDoBanco(String pacote) {
        if (pacote.contains("xp")) return "XP";
        if (pacote.contains("intermedium")) return "Inter";
        if (pacote.contains("mercadopago")) return "Mercado Pago";
        if (pacote.contains("nu.production")) return "Nubank";
        if (pacote.contains("itau")) return "Itaú";
        return pacote;
    }
}
