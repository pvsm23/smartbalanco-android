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

    /**
     * A instância viva do serviço, para o plugin conseguir pedir uma varredura.
     *
     * O serviço é criado pelo Android, não por nós — não há como alcançá-lo de
     * fora sem guardar a referência aqui. Ela é limpa ao desconectar: segurar
     * um serviço morto faria a varredura falhar em silêncio, que é exatamente
     * o defeito que este botão existe para acabar.
     */
    private static LeitorNotificacoes instancia = null;

    /** Evita empilhar agendamentos: um basta para a janela inteira. */
    private boolean avisoAgendado = false;
    private final Handler relogio = new Handler(Looper.getMainLooper());

    /**
     * Pacotes observados. Qualquer outro é ignorado sem ser lido.
     *
     * Só XP e Inter, por escolha: são os dois cartões que interessam. Cada
     * pacote a mais é uma fonte a mais de notificação para filtrar errado.
     */
    private static final String[] PACOTES = {
        // O app da conta e do cartão XP é br.com.xp.carteira. O prefixo com
        // ponto cobre os outros apps da casa sem pegar um "br.com.xpto"
        // qualquer, já que a comparação é por startsWith.
        //
        // Os nomes anteriores ("com.xp.investimentos", "br.com.xpi") eram
        // palpite meu e não existem: como pacote fora da lista é descartado na
        // primeira linha, TODA notificação do XP era jogada fora em silêncio --
        // sem erro, sem registro, sem sintoma. Nome de pacote se confere, não
        // se adivinha.
        "br.com.xp.",                // XP
        "br.com.intermedium"         // Inter
    };

    /** Os mesmos nomes, para a tela de diagnóstico poder mostrá-los. */
    public static String pacotesObservados() {
        return android.text.TextUtils.join(", ", PACOTES);
    }

    /**
     * "R$ 1.234,56" ou "R$ 12,90" — com ou sem espaço depois do R$.
     *
     * Os espaços especiais estão na classe além do \s por cinto e suspensório:
     * o texto já chega normalizado, mas esta expressão também é usada sobre
     * texto cru em teste, e um valor não lido custa uma compra perdida.
     */
    private static final Pattern VALOR =
        Pattern.compile("R\\$[\\s\\u00A0\\u202F\\u2007\\u2009]*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2})");

    /**
     * O estabelecimento costuma vir depois de "em" ou "no/na":
     *   "Compra aprovada de R$ 32,90 em PADARIA CENTRAL"
     */
    private static final Pattern ESTABELECIMENTO =
        Pattern.compile("(?:\\bem|\\bno|\\bna)\\s+([A-Z0-9][^.,;\\n]{2,40})");

    /**
     * Sinais de que a notificação é uma COMPRA NO CRÉDITO.
     *
     * Precisa de pelo menos um destes. Sem isto, "tem valor em reais" seria o
     * único critério — e aviso de saldo, fatura fechada e Pix recebido também
     * têm valor em reais.
     */
    private static final String[] SINAIS_DE_COMPRA = {
        "compra", "credito", "cartao", "aprovada", "aprovado", "parcelad"
    };

    /**
     * Se qualquer um destes aparecer, NÃO é compra no crédito, mesmo que um
     * sinal acima também apareça. A exclusão vence de propósito: é melhor
     * perder uma compra (você lança à mão) do que lançar um Pix recebido como
     * despesa — o erro que passa despercebido na hora de aprovar.
     *
     * "debito" está aqui porque compra no débito também não interessa.
     */
    private static final String[] NAO_E_COMPRA = {
        "pix", "transferencia", "ted", "doc ", "boleto", "deposito",
        "recebeu", "recebido", "recebida", "estorno", "estornad",
        "cancelad", "saldo", "fatura", "salario", "rendimento",
        "investimento", "resgate", "aplicacao", "saque", "debito",
        "cashback", "limite", "vencimento", "cobranca", "assinatura"
    };

    /** Quantas notificações descartadas guardar, para ajustar os padrões. */
    private static final int LIMITE_IGNORADOS = 25;
    public static final String CHAVE_IGNORADOS = "ignorados";
    public static final String CHAVE_ULTIMA_VISTA = "ultimaVista";
    public static final String CHAVE_CONECTADO = "conectadoDesde";

    /**
     * O Android avisa aqui quando o serviço foi realmente ligado à barra de
     * notificações. Ter permissão concedida e estar CONECTADO são coisas
     * diferentes: atualizar o app derruba a conexão, e ela costuma voltar só
     * quando a permissão é desligada e religada à mão.
     *
     * Sem este carimbo, "não capturei nada" tem duas explicações iguais na
     * tela -- não houve compra, ou o serviço nunca ligou.
     */
    @Override
    public void onListenerConnected() {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(CHAVE_CONECTADO, System.currentTimeMillis()).apply();
            instancia = this;
            Log.i(TAG, "Serviço conectado à barra de notificações.");

            // Varre o que já está na barra agora. A notificação do banco que
            // chegou antes do serviço ligar ficaria perdida — o Android entrega
            // cada uma só uma vez, e esta é a única chance de relê-la.
            StatusBarNotification[] ativas = getActiveNotifications();
            if (ativas != null) {
                for (StatusBarNotification sbn : ativas) onNotificationPosted(sbn);
            }
        } catch (Exception e) {
            Log.w(TAG, "Falha ao conectar: " + e.getMessage());
        }
    }

    /**
     * Desconectou. Pede para o Android religar em vez de esperar: sem isto, uma
     * queda do serviço só se resolve reiniciando o aparelho.
     */
    @Override
    public void onListenerDisconnected() {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(CHAVE_CONECTADO, 0).apply();
            instancia = null;
            Log.w(TAG, "Serviço desconectado; pedindo religação.");

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                requestRebind(new android.content.ComponentName(this, LeitorNotificacoes.class));
            }
        } catch (Exception e) {
            Log.w(TAG, "Falha ao pedir religação: " + e.getMessage());
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            final String pacote = sbn.getPackageName();
            if (!ehPacoteObservado(pacote)) return;   // descarta antes de ler

            Bundle extras = sbn.getNotification().extras;

            // Junta TODOS os campos de texto, em vez de escolher um. Cada app
            // monta a notificação de um jeito: o valor pode estar no corpo, no
            // texto expandido, no resumo ou numa das linhas de uma lista. Ler
            // só "android.text" era apostar num formato.
            String completo = juntarTextos(extras);
            if (completo.isEmpty()) return;

            // Os bancos usam espaço NÃO SEPARÁVEL entre "R$" e o número, para
            // o valor não quebrar de linha. O \\s das expressões do Java não
            // casa com ele, e por isso "R$ 32,99" podia não ser reconhecido
            // como valor nenhum. Normalizar aqui conserta a leitura inteira de
            // uma vez, em vez de remendar cada expressão.
            completo = normalizarEspacos(completo);

            // Carimba que o serviço está vivo e recebendo. É o que a tela de
            // diagnóstico mostra: sem isto, "nada capturado" pode ser tanto
            // "não comprei nada" quanto "a permissão está desligada", e as
            // duas coisas parecem iguais na tela.
            marcarQueChegouAlgo();

            // Sem valor em reais não é compra: corta propaganda e aviso de
            // login antes de qualquer análise.
            Matcher mv = VALOR.matcher(completo);
            if (!mv.find()) {
                // Registrado, e não descartado em silêncio. Foi exatamente este
                // buraco que escondeu o defeito: a notificação chegava, não era
                // capturada, não aparecia como ignorada, e a tela não tinha como
                // dizer que ela existiu.
                registrarIgnorada(nomeDoBanco(pacote), completo, "", "sem valor em reais");
                return;
            }

            String valorTexto = mv.group(1);

            // Só compra no crédito. O que for descartado fica registrado, para
            // dar para ajustar os padrões se um dia o banco mudar o texto e
            // uma compra de verdade parar de ser reconhecida.
            String motivo = porQueNaoEhCompra(completo);
            if (motivo != null) {
                registrarIgnorada(nomeDoBanco(pacote), completo, valorTexto, motivo);
                return;
            }

            String estabelecimento = "";
            Matcher me = ESTABELECIMENTO.matcher(completo);
            if (me.find()) estabelecimento = limparEstabelecimento(me.group(1));

            guardar(pacote, titulo, corpo, valorTexto, estabelecimento,
                    sbn.getPostTime());

        } catch (Exception e) {
            // Um erro aqui não pode derrubar o serviço: ele perderia as
            // próximas notificações até o Android reiniciá-lo.
            Log.w(TAG, "Falha ao ler notificação: " + e.getMessage());
        }
    }

    /**
     * Devolve o motivo de NÃO ser compra no crédito, ou null se for.
     *
     * Devolver o motivo (em vez de um booleano) é o que deixa a tela explicar
     * o descarte. "Ignorei 4" não ajuda ninguém; "ignorei porque é Pix" ajuda.
     */
    private String porQueNaoEhCompra(String texto) {
        String t = semAcento(texto).toLowerCase();

        for (String termo : NAO_E_COMPRA) {
            if (t.contains(termo)) return "tem \"" + termo.trim() + "\"";
        }
        for (String sinal : SINAIS_DE_COMPRA) {
            if (t.contains(sinal)) return null;
        }
        return "não parece compra no crédito";
    }

    /**
     * Tira os acentos para a comparação. Sem isto, "cartão" e "cartao" seriam
     * palavras diferentes, e o texto do banco varia.
     */
    private String semAcento(String t) {
        return java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                   .replaceAll("\\p{M}+", "");
    }

    /** Carimba a hora da última notificação recebida dos bancos observados. */
    private void marcarQueChegouAlgo() {
        try {
            getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(CHAVE_ULTIMA_VISTA, System.currentTimeMillis()).apply();
        } catch (Exception e) { /* diagnóstico não pode derrubar a captura */ }
    }

    /**
     * Guarda o que foi descartado, com o texto original e o motivo.
     *
     * Isto NÃO vira lançamento e não sai do aparelho sozinho. Existe para um
     * caso concreto: o banco muda o texto da notificação, uma compra de verdade
     * passa a ser descartada, e sem este registro o sintoma seria silêncio —
     * você só perceberia semanas depois, ao conferir a fatura.
     */
    private void registrarIgnorada(String banco, String texto, String valor, String motivo) {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray lista = new JSONArray(prefs.getString(CHAVE_IGNORADOS, "[]"));

            JSONObject item = new JSONObject();
            item.put("app", banco);
            item.put("texto", texto);
            item.put("valor", valor);
            item.put("motivo", motivo);
            item.put("quando", System.currentTimeMillis());
            lista.put(item);

            while (lista.length() > LIMITE_IGNORADOS) lista.remove(0);
            prefs.edit().putString(CHAVE_IGNORADOS, lista.toString()).apply();

            Log.i(TAG, "Ignorada (" + motivo + "): " + texto);
        } catch (Exception e) {
            Log.w(TAG, "Falha ao registrar ignorada: " + e.getMessage());
        }
    }

    /**
     * Relê as notificações que estão na barra AGORA, a pedido do app.
     *
     * Existe porque o caminho normal é cego: o Android entrega cada notificação
     * uma única vez, e se o serviço estava desligado naquele instante — app
     * recém-atualizado, aparelho recém-ligado — a compra se perdeu sem deixar
     * rastro. Enquanto o aviso do banco continuar na barra, isto o recupera.
     *
     * Devolve a CONTA do que encontrou em cada etapa, e não só o total. É a
     * diferença entre "não achei nada" e "achei 14 notificações, nenhuma dos
     * seus bancos" — a segunda diz onde está o problema.
     */
    public static String varrerAtivas() {
        if (instancia == null) return "servico-desligado";
        return instancia.varrer();
    }

    private String varrer() {
        int total = 0, dosBancos = 0, capturadas = 0;
        try {
            StatusBarNotification[] ativas = getActiveNotifications();
            if (ativas == null) return "0|0|0";

            total = ativas.length;
            for (StatusBarNotification sbn : ativas) {
                if (!ehPacoteObservado(sbn.getPackageName())) continue;
                dosBancos++;

                int antes = tamanhoDaFila();
                onNotificationPosted(sbn);
                if (tamanhoDaFila() > antes) capturadas++;
            }
        } catch (Exception e) {
            Log.w(TAG, "Falha na varredura: " + e.getMessage());
            return "erro:" + e.getMessage();
        }
        Log.i(TAG, "Varredura: " + total + " na barra, " + dosBancos +
                   " dos bancos, " + capturadas + " novas.");
        return total + "|" + dosBancos + "|" + capturadas;
    }

    private int tamanhoDaFila() {
        try {
            SharedPreferences prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return new JSONArray(prefs.getString(CHAVE_FILA, "[]")).length();
        } catch (Exception e) { return 0; }
    }

    /**
     * Corta o que vem depois do nome da loja.
     *
     * "AMAZON BR via cartão digital" é o nome mais um detalhe do meio de
     * pagamento; o que entra na descrição da despesa é só a primeira parte.
     */
    private String limparEstabelecimento(String bruto) {
        String t = bruto.trim();
        String[] cortes = { " via ", " no cartao ", " no cartão ", " com o ", " usando " };
        for (String corte : cortes) {
            int i = t.toLowerCase().indexOf(corte);
            if (i > 0) t = t.substring(0, i);
        }
        return t.trim();
    }

    private boolean ehPacoteObservado(String pacote) {
        if (pacote == null) return false;
        for (String p : PACOTES) {
            if (pacote.startsWith(p)) return true;
        }
        return false;
    }

    /**
     * Junta título, corpo, texto expandido, resumo e as linhas de lista.
     *
     * Campos repetidos não atrapalham: o que se procura aqui é um valor em
     * reais e algumas palavras, e ambos aguentam repetição.
     */
    private String juntarTextos(Bundle extras) {
        StringBuilder sb = new StringBuilder();
        String[] chaves = {
            "android.title", "android.text", "android.bigText",
            "android.summaryText", "android.subText", "android.infoText"
        };
        for (String c : chaves) {
            String v = textoDe(extras, c);
            if (!v.isEmpty()) sb.append(v).append(" ");
        }

        // Notificação em lista (InboxStyle) guarda o conteúdo aqui.
        try {
            CharSequence[] linhas = extras.getCharSequenceArray("android.textLines");
            if (linhas != null) {
                for (CharSequence l : linhas) if (l != null) sb.append(l).append(" ");
            }
        } catch (Exception e) { /* formato inesperado não pode derrubar a leitura */ }

        return sb.toString().trim();
    }

    /**
     * Troca por espaço comum os espaços especiais do Unicode: o não separável
     * (U+00A0), o estreito (U+202F), o de dígito (U+2007) e o fino (U+2009).
     */
    private String normalizarEspacos(String t) {
        return t.replace('\u00A0', ' ')
                .replace('\u202F', ' ')
                .replace('\u2007', ' ')
                .replace('\u2009', ' ')
                .replaceAll(" {2,}", " ");
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
        return pacote;
    }
}
