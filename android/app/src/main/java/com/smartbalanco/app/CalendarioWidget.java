package com.smartbalanco.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Widget de calendário: o mês inteiro, com o dinheiro diminuindo dia a dia.
 *
 * Cada dia é uma célula colorida pelo quanto da COTA daquele dia foi gasta. A
 * cota é recalculada todo dia (o que sobra ÷ dias que faltam), então um dia
 * ruim aperta o dia seguinte -- é isso que faz o número servir para decidir
 * uma compra, e não só para informar.
 *
 * Tocar num dia faz a célula crescer no lugar e abre a faixa de detalhe logo
 * abaixo daquela semana. Nenhuma semana sai da posição: a de cima fica parada
 * e as de baixo descem. Crescer para os lados empurraria os vizinhos e tiraria
 * o dia 18 da quinta-feira.
 *
 * O widget NÃO fala com a planilha. Ele desenha o que o app deixou guardado, e
 * o serviço de atualização (AtualizarWidgetService) é quem busca. Por isso a
 * hora da última atualização fica sempre à vista -- número velho sem aviso é
 * pior que número nenhum.
 */
public class CalendarioWidget extends AppWidgetProvider {

    private static final String TAG = "SmartbalancoWidget";

    public static final String PREFS = "smartbalanco_widget";
    public static final String CHAVE_RESUMO = "resumo";
    public static final String CHAVE_QUANDO = "quando";

    private static final String ACAO_DIA = "com.smartbalanco.app.WIDGET_DIA";
    private static final String ACAO_ATUALIZAR = "com.smartbalanco.app.WIDGET_ATUALIZAR";
    private static final String EXTRA_DIA = "dia";

    /** Qual dia está aberto, por widget. 0 = nenhum. */
    private static final String CHAVE_ABERTO = "aberto_";

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        for (int id : ids) desenhar(ctx, mgr, id);
    }

    @Override
    public void onReceive(Context ctx, Intent intent) {
        super.onReceive(ctx, intent);
        final String acao = intent.getAction();
        if (acao == null) return;

        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);

        if (ACAO_DIA.equals(acao)) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);
            int dia = intent.getIntExtra(EXTRA_DIA, 0);
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

            // Tocar no dia já aberto fecha. Sem isso não haveria como voltar à
            // visão do mês sem sair do widget.
            int atual = prefs.getInt(CHAVE_ABERTO + id, 0);
            prefs.edit().putInt(CHAVE_ABERTO + id, atual == dia ? 0 : dia).apply();

            desenhar(ctx, mgr, id);
            return;
        }

        if (ACAO_ATUALIZAR.equals(acao)) {
            int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, 0);

            // Aviso imediato de que algo está acontecendo: a busca leva alguns
            // segundos e um botão que não responde parece quebrado.
            RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_calendario);
            rv.setTextViewText(R.id.btAtualizar, "buscando");
            mgr.partiallyUpdateAppWidget(id, rv);

            AtualizarWidgetService.disparar(ctx);
            return;
        }
    }

    /** Redesenha todos os widgets colocados na tela. */
    public static void redesenharTodos(Context ctx) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, CalendarioWidget.class));
        for (int id : ids) desenhar(ctx, mgr, id);
    }

    private static void desenhar(Context ctx, AppWidgetManager mgr, int widgetId) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_calendario);
        SharedPreferences prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        JSONObject r = null;
        try {
            String bruto = prefs.getString(CHAVE_RESUMO, "");
            if (!bruto.isEmpty()) r = new JSONObject(bruto);
        } catch (Exception e) {
            Log.w(TAG, "Resumo ilegível: " + e.getMessage());
        }

        if (r == null) {
            // Ainda não há dado nenhum. Dizer isso é melhor que mostrar um
            // calendário vazio que parece um mês sem gastos.
            rv.setTextViewText(R.id.rotuloCota, "toque para carregar");
            rv.setTextViewText(R.id.cota, "--");
            rv.setTextViewText(R.id.sobra, "");
            rv.setTextViewText(R.id.atualizado, "");
            ligarBotoes(ctx, rv, widgetId);
            mgr.updateAppWidget(widgetId, rv);
            return;
        }

        try {
            int hoje = r.optInt("hoje", 1);
            int diasNoMes = r.optInt("diasNoMes", 30);
            int primeiro = r.optInt("primeiroDiaSemana", 0);
            int diasRestantes = Math.max(1, r.optInt("diasRestantes", 1));

            double cota = r.optDouble("cotaHoje", 0);
            double sobra = r.optDouble("sobra", 0);

            JSONArray gastos = r.optJSONArray("gastos");
            JSONArray vence = r.optJSONArray("vence");
            JSONArray nomes = r.optJSONArray("nomes");
            JSONArray fecha = r.optJSONArray("fechamentos");

            int aberto = prefs.getInt(CHAVE_ABERTO + widgetId, 0);

            // ---------- topo ----------
            boolean vencido = sobra < 0;
            rv.setTextViewText(R.id.rotuloCota, vencido ? "você já passou do mês" : "hoje você pode gastar");
            rv.setTextViewText(R.id.cota, dinheiro(Math.abs(cota)));
            rv.setTextColor(R.id.cota, vencido ? 0xFFF09595 : 0xFF5DCAA5);
            rv.setTextViewText(R.id.sobra, "restam " + dinheiro(sobra));
            rv.setTextViewText(R.id.atualizado, haQuantoTempo(prefs.getLong(CHAVE_QUANDO, 0)));

            // ---------- grade ----------
            for (int i = 0; i < 42; i++) {
                int dia = i - primeiro + 1;
                boolean valido = dia >= 1 && dia <= diasNoMes;

                int idCel = idDe(ctx, "cel" + i);
                int idDia = idDe(ctx, "dia" + i);
                int idSub = idDe(ctx, "sub" + i);
                if (idCel == 0) continue;

                if (!valido) {
                    rv.setViewVisibility(idCel, android.view.View.INVISIBLE);
                    continue;
                }
                rv.setViewVisibility(idCel, android.view.View.VISIBLE);
                rv.setTextViewText(idDia, String.valueOf(dia));

                double g = valorDe(gastos, dia - 1);
                double v = valorDe(vence, dia - 1);
                boolean futuro = dia > hoje;
                boolean selecionado = dia == aberto;

                rv.setInt(idCel, "setBackgroundResource",
                          fundoDaCelula(ctx, g, cota, futuro, selecionado, dia == hoje));
                rv.setTextColor(idDia, futuro ? 0xFF8FB0D9 : 0xFF0B1B2E);

                // O texto de baixo é o que faz a célula crescer. Fora da
                // seleção ele vira o ponto de conta a vencer, em vez de mais
                // uma view só para isso.
                if (selecionado) {
                    rv.setViewVisibility(idSub, android.view.View.VISIBLE);
                    rv.setTextViewText(idSub, futuro ? (v > 0 ? "vence" : "--") : curto(g));
                    rv.setTextColor(idSub, futuro ? 0xFFB5D4F4 : 0xFF0B1B2E);
                    rv.setTextViewTextSize(idDia, android.util.TypedValue.COMPLEX_UNIT_SP, 12);
                } else if (v > 0) {
                    rv.setViewVisibility(idSub, android.view.View.VISIBLE);
                    rv.setTextViewText(idSub, "•");
                    rv.setTextColor(idSub, 0xFFB5D4F4);
                    rv.setTextViewTextSize(idDia, android.util.TypedValue.COMPLEX_UNIT_SP, 10);
                } else {
                    rv.setViewVisibility(idSub, android.view.View.GONE);
                    rv.setTextViewTextSize(idDia, android.util.TypedValue.COMPLEX_UNIT_SP, 10);
                }

                rv.setOnClickPendingIntent(idCel, intentDoDia(ctx, widgetId, dia));
            }

            // ---------- faixa de detalhe ----------
            for (int s = 0; s < 6; s++) {
                int idFaixa = idDe(ctx, "faixa" + s);
                if (idFaixa == 0) continue;

                boolean estaSemana = aberto > 0 && semanaDoDia(aberto, primeiro) == s;
                rv.setViewVisibility(idFaixa, estaSemana ? android.view.View.VISIBLE
                                                         : android.view.View.GONE);
                if (!estaSemana) continue;

                double g = valorDe(gastos, aberto - 1);
                double v = valorDe(vence, aberto - 1);
                boolean futuro = aberto > hoje;

                rv.setTextViewText(idDe(ctx, "faixaTitulo" + s), aberto + " de " + nomeDoMes(r.optInt("mes", 0)));

                int idResumo = idDe(ctx, "faixaResumo" + s);
                int idLista = idDe(ctx, "faixaLista" + s);

                if (futuro) {
                    rv.setTextViewText(idResumo, v > 0 ? "a pagar" : "");
                    rv.setTextColor(idResumo, 0xFFB5D4F4);
                    String nome = textoDe(nomes, aberto - 1);
                    rv.setTextViewText(idLista, v > 0
                        ? (nome.isEmpty() ? "conta a vencer" : nome) + "   " + dinheiro(v)
                        : "nada previsto para este dia");
                } else {
                    double folga = cota - g;
                    rv.setTextViewText(idResumo, (folga < 0 ? "estourou " : "sobrou ") + dinheiro(Math.abs(folga)));
                    rv.setTextColor(idResumo, folga < 0 ? 0xFFF09595 : 0xFF5DCAA5);
                    rv.setTextViewText(idLista, g > 0
                        ? "gastou " + dinheiro(g) + " neste dia"
                        : "nada lançado neste dia");
                }
            }

            // ---------- rodapé ----------
            int pendentes = r.optInt("pendentes", 0);
            rv.setViewVisibility(R.id.btPendentes,
                pendentes > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
            if (pendentes > 0) {
                rv.setTextViewText(R.id.btPendentes, "conferir " + pendentes);
            }
            rv.setTextViewText(R.id.btAtualizar, "atualizar");

            ligarBotoes(ctx, rv, widgetId);
            mgr.updateAppWidget(widgetId, rv);

        } catch (Exception e) {
            Log.w(TAG, "Falha ao desenhar: " + e.getMessage());
        }
    }

    /**
     * Escolhe o fundo da célula.
     *
     * Um recurso por combinação de cor e contorno porque RemoteViews só troca o
     * desenho inteiro -- mudar a cor de um fundo arredondado precisa do
     * Android 12, e o app vale do 7 em diante.
     */
    private static int fundoDaCelula(Context ctx, double gasto, double cota,
                                     boolean futuro, boolean selecionado, boolean ehHoje) {
        String base;
        if (futuro) base = "cel_futuro";
        else if (cota > 0 && gasto > cota) base = "cel_vermelho";
        else if (cota > 0 && gasto > cota * 0.75) base = "cel_amarelo";
        else base = "cel_verde";

        if (selecionado) base += "_sel";
        else if (ehHoje) base += "_hoje";

        return ctx.getResources().getIdentifier(base, "drawable", ctx.getPackageName());
    }

    private static void ligarBotoes(Context ctx, RemoteViews rv, int widgetId) {
        rv.setOnClickPendingIntent(R.id.btFalar, abrirApp(ctx, "voz", 1));
        rv.setOnClickPendingIntent(R.id.btFoto, abrirApp(ctx, "novo-documento", 2));
        rv.setOnClickPendingIntent(R.id.btPendentes, abrirApp(ctx, "aprovacoes", 3));

        Intent i = new Intent(ctx, CalendarioWidget.class);
        i.setAction(ACAO_ATUALIZAR);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        rv.setOnClickPendingIntent(R.id.btAtualizar, PendingIntent.getBroadcast(
            ctx, 4000 + widgetId, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }

    private static PendingIntent abrirApp(Context ctx, String destino, int codigo) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setAction(Intent.ACTION_VIEW);
        i.setData(Uri.parse("com.smartbalanco.app://" + destino));
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(ctx, codigo, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /**
     * O toque no dia é um broadcast para o próprio widget, não abre o app.
     *
     * O código do PendingIntent precisa ser único por dia: com o mesmo código,
     * o Android reaproveita o intent anterior e todos os dias abririam o
     * primeiro que foi criado.
     */
    private static PendingIntent intentDoDia(Context ctx, int widgetId, int dia) {
        Intent i = new Intent(ctx, CalendarioWidget.class);
        i.setAction(ACAO_DIA);
        i.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
        i.putExtra(EXTRA_DIA, dia);
        i.setData(Uri.parse("smartbalanco://dia/" + widgetId + "/" + dia));
        return PendingIntent.getBroadcast(ctx, widgetId * 100 + dia, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int semanaDoDia(int dia, int primeiroDiaSemana) {
        return (dia + primeiroDiaSemana - 1) / 7;
    }

    private static int idDe(Context ctx, String nome) {
        return ctx.getResources().getIdentifier(nome, "id", ctx.getPackageName());
    }

    private static double valorDe(JSONArray a, int i) {
        if (a == null || i < 0 || i >= a.length()) return 0;
        return a.optDouble(i, 0);
    }

    private static String textoDe(JSONArray a, int i) {
        if (a == null || i < 0 || i >= a.length()) return "";
        return a.optString(i, "");
    }

    /** "R$ 1.240" -- sem centavos, que não cabem e não decidem nada. */
    private static String dinheiro(double v) {
        return "R$ " + String.format(java.util.Locale.forLanguageTag("pt-BR"), "%,.0f", v);
    }

    /** Dentro da célula o espaço é de três caracteres. */
    private static String curto(double v) {
        if (v >= 1000) return String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1fk", v / 1000);
        return String.valueOf(Math.round(v));
    }

    private static String haQuantoTempo(long quando) {
        if (quando <= 0) return "nunca atualizado";
        long min = (System.currentTimeMillis() - quando) / 60000;
        if (min < 2) return "atualizado agora";
        if (min < 60) return "atualizado há " + min + " min";
        long h = min / 60;
        if (h < 24) return "atualizado há " + h + " h";
        return "atualizado há " + (h / 24) + " d";
    }

    private static String nomeDoMes(int m) {
        String[] meses = { "janeiro", "fevereiro", "março", "abril", "maio", "junho",
                           "julho", "agosto", "setembro", "outubro", "novembro", "dezembro" };
        return (m >= 0 && m < 12) ? meses[m] : "";
    }
}
