package com.smartbalanco.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;

/**
 * Widget da tela inicial: mostra a próxima conta a vencer e o total dos
 * próximos 15 dias.
 *
 * De onde vêm os dados: o app (que roda numa WebView) grava um resumo usando
 * o plugin @capacitor/preferences, que por baixo escreve no SharedPreferences
 * chamado "CapacitorStorage". O widget é um processo separado da WebView e
 * não consegue chamar JavaScript, então este arquivo compartilhado é a ponte.
 *
 * Quando atualiza:
 *  - a cada 30 minutos (mínimo que o Android aceita em updatePeriodMillis);
 *  - quando o app termina de carregar o dashboard e chama WidgetPlugin.atualizar();
 *  - ao ser adicionado à tela.
 */
public class SmartbalancoWidget extends AppWidgetProvider {

    private static final String ARQUIVO_PREFS = "CapacitorStorage";

    @Override
    public void onUpdate(Context context, AppWidgetManager gerenciador, int[] ids) {
        for (int id : ids) {
            desenhar(context, gerenciador, id);
        }
    }

    /** Redesenha todos os widgets. Chamado pelo app quando os dados mudam. */
    public static void atualizarTodos(Context context) {
        AppWidgetManager gerenciador = AppWidgetManager.getInstance(context);
        ComponentName nome = new ComponentName(context, SmartbalancoWidget.class);
        int[] ids = gerenciador.getAppWidgetIds(nome);
        for (int id : ids) {
            desenhar(context, gerenciador, id);
        }
    }

    private static void desenhar(Context context, AppWidgetManager gerenciador, int id) {
        SharedPreferences prefs = context.getSharedPreferences(ARQUIVO_PREFS, Context.MODE_PRIVATE);

        String descricao = prefs.getString("widget_proxima_desc", null);
        String valor = prefs.getString("widget_proxima_valor", null);
        String data = prefs.getString("widget_proxima_data", null);
        String total = prefs.getString("widget_total", null);
        String atualizado = prefs.getString("widget_atualizado", null);

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_smartbalanco);

        // Enquanto o app não abriu nenhuma vez, não há nada gravado.
        if (descricao == null || descricao.isEmpty()) {
            views.setTextViewText(R.id.widget_titulo, "Smartbalanço");
            views.setTextViewText(R.id.widget_descricao, "Abra o app para carregar");
            views.setTextViewText(R.id.widget_valor, "—");
            views.setTextViewText(R.id.widget_data, "");
            views.setTextViewText(R.id.widget_rodape, "");
        } else {
            views.setTextViewText(R.id.widget_titulo, "Próxima conta");
            views.setTextViewText(R.id.widget_descricao, descricao);
            views.setTextViewText(R.id.widget_valor, valor == null ? "" : valor);
            views.setTextViewText(R.id.widget_data, data == null ? "" : data);

            String rodape = "";
            if (total != null && !total.isEmpty()) rodape = "15 dias: " + total;
            if (atualizado != null && !atualizado.isEmpty()) {
                rodape = rodape.isEmpty() ? atualizado : rodape + " · " + atualizado;
            }
            views.setTextViewText(R.id.widget_rodape, rodape);
        }

        // Tocar em qualquer parte abre o app
        Intent abrir = new Intent(context, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent aoTocar = PendingIntent.getActivity(
            context, 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_raiz, aoTocar);

        gerenciador.updateAppWidget(id, views);
    }
}
