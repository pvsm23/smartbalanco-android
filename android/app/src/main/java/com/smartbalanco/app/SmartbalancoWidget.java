package com.smartbalanco.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.RemoteViews;

/**
 * Widget da tela inicial: todas as despesas do próximo dia que tem conta a
 * vencer, com o total daquele dia.
 *
 * Cresce na vertical — quanto mais alto, mais linhas aparecem — porque a
 * lista é um ListView alimentado pelo DespesasWidgetService, e não linhas
 * fixas no layout.
 *
 * De onde vêm os dados: o app (numa WebView) grava um resumo usando o plugin
 * @capacitor/preferences, que por baixo escreve no SharedPreferences
 * "CapacitorStorage". O widget é um processo separado e não consegue chamar
 * JavaScript, então esse arquivo compartilhado é a ponte.
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
        // Avisa a lista que o JSON mudou: sem isso a Factory continua servindo
        // o conteúdo antigo, mesmo com o resto do widget já atualizado.
        gerenciador.notifyAppWidgetViewDataChanged(ids, R.id.widget_lista);
    }

    private static void desenhar(Context context, AppWidgetManager gerenciador, int id) {
        SharedPreferences prefs = context.getSharedPreferences(ARQUIVO_PREFS, Context.MODE_PRIVATE);

        String data = prefs.getString("widget_dia", null);
        String total = prefs.getString("widget_dia_total", null);
        String quantidade = prefs.getString("widget_dia_qtd", null);
        String atualizado = prefs.getString("widget_atualizado", null);
        String lista = prefs.getString("widget_lista", "[]");

        boolean temDados = lista != null && !lista.isEmpty() && !lista.equals("[]");

        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_smartbalanco);

        views.setTextViewText(R.id.widget_data, data == null ? "" : data);
        views.setTextViewText(R.id.widget_total, total == null ? "" : total);

        String rodape = "";
        if (quantidade != null && !quantidade.isEmpty()) rodape = quantidade;
        if (atualizado != null && !atualizado.isEmpty()) {
            rodape = rodape.isEmpty() ? atualizado : rodape + " · " + atualizado;
        }
        views.setTextViewText(R.id.widget_rodape, rodape);

        views.setViewVisibility(R.id.widget_vazio, temDados ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.widget_lista, temDados ? View.VISIBLE : View.GONE);

        // Liga a lista ao serviço que a preenche
        Intent servico = new Intent(context, DespesasWidgetService.class);
        servico.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id);
        // O data único evita que o Android reaproveite o adapter de outro
        // widget e mostre a lista errada quando há mais de um na tela.
        servico.setData(android.net.Uri.parse(servico.toUri(Intent.URI_INTENT_SCHEME)));
        views.setRemoteAdapter(R.id.widget_lista, servico);
        views.setEmptyView(R.id.widget_lista, R.id.widget_vazio);

        // Tocar no widget (ou numa linha) abre o app
        Intent abrir = new Intent(context, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent aoTocar = PendingIntent.getActivity(
            context, 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(R.id.widget_titulo, aoTocar);
        views.setPendingIntentTemplate(R.id.widget_lista, aoTocar);

        gerenciador.updateAppWidget(id, views);
    }
}
