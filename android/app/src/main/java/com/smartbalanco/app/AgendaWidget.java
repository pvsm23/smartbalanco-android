package com.smartbalanco.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.view.View;
import android.widget.RemoteViews;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Widget do Smartcalendário: os próximos compromissos e contas, em lista.
 *
 * Escolhi lista em vez da grade do mês porque num widget a grade vira um
 * amontoado de números pequenos — a lista responde "o que vem por aí" de
 * relance, que é o que se olha na tela inicial.
 *
 * São 4 linhas fixas no layout, não um ListView: sem lista rolável não é
 * preciso um RemoteViewsService só para isso, e 4 itens cobrem o widget 4x2.
 *
 * Os dados são gravados pelo app quando o Smartcalendário é aberto — é lá que
 * compromissos e despesas do mês já estão em mãos, sem custar uma consulta a
 * mais ao servidor.
 */
public class AgendaWidget extends AppWidgetProvider {

    private static final String ARQUIVO_PREFS = "CapacitorStorage";
    private static final int[] LINHAS = { R.id.ag_1, R.id.ag_2, R.id.ag_3, R.id.ag_4 };
    private static final int[] DATAS  = { R.id.ag_1_data, R.id.ag_2_data, R.id.ag_3_data, R.id.ag_4_data };
    private static final int[] TEXTOS = { R.id.ag_1_txt, R.id.ag_2_txt, R.id.ag_3_txt, R.id.ag_4_txt };

    @Override
    public void onUpdate(Context context, AppWidgetManager gerenciador, int[] ids) {
        for (int id : ids) desenhar(context, gerenciador, id);
    }

    public static void atualizarTodos(Context context) {
        AppWidgetManager g = AppWidgetManager.getInstance(context);
        int[] ids = g.getAppWidgetIds(new ComponentName(context, AgendaWidget.class));
        for (int id : ids) desenhar(context, g, id);
    }

    private static void desenhar(Context context, AppWidgetManager gerenciador, int id) {
        SharedPreferences prefs = context.getSharedPreferences(ARQUIVO_PREFS, Context.MODE_PRIVATE);
        String bruto = prefs.getString("widget_agenda", "[]");

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_agenda);

        JSONArray itens;
        try { itens = new JSONArray(bruto == null ? "[]" : bruto); }
        catch (Exception e) { itens = new JSONArray(); }

        boolean vazio = itens.length() == 0;
        views.setViewVisibility(R.id.ag_vazio, vazio ? View.VISIBLE : View.GONE);

        for (int i = 0; i < LINHAS.length; i++) {
            if (i < itens.length()) {
                JSONObject item = itens.optJSONObject(i);
                views.setViewVisibility(LINHAS[i], View.VISIBLE);
                views.setTextViewText(DATAS[i], item != null ? item.optString("data", "") : "");
                views.setTextViewText(TEXTOS[i], item != null ? item.optString("texto", "") : "");
            } else {
                views.setViewVisibility(LINHAS[i], View.GONE);
            }
        }

        // Abre direto no Smartcalendário
        Intent abrir = new Intent(context, MainActivity.class);
        abrir.setAction(Intent.ACTION_VIEW);
        abrir.setData(Uri.parse("com.smartbalanco.app://calendario"));
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        views.setOnClickPendingIntent(R.id.ag_raiz, PendingIntent.getActivity(
            context, 20, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));

        gerenciador.updateAppWidget(id, views);
    }
}
