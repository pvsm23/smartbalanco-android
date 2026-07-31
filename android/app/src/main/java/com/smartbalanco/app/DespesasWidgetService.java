package com.smartbalanco.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Alimenta a lista do widget.
 *
 * Um widget só mostra lista de verdade (que rola e acompanha o
 * redimensionamento) através de um RemoteViewsService: o AppWidgetProvider
 * sozinho não consegue criar linhas dinamicamente.
 *
 * Os dados vêm do mesmo SharedPreferences que o app escreve pelo plugin
 * Preferences — aqui é lido o JSON gravado em "widget_lista".
 */
public class DespesasWidgetService extends RemoteViewsService {

    @Override
    public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new DespesasFactory(getApplicationContext());
    }

    private static class DespesasFactory implements RemoteViewsService.RemoteViewsFactory {

        private static final String ARQUIVO_PREFS = "CapacitorStorage";
        private final Context context;
        private JSONArray itens = new JSONArray();

        DespesasFactory(Context context) {
            this.context = context;
        }

        /** Relê o JSON. Chamado a cada notifyAppWidgetViewDataChanged. */
        private void carregar() {
            SharedPreferences prefs = context.getSharedPreferences(ARQUIVO_PREFS, Context.MODE_PRIVATE);
            String bruto = prefs.getString("widget_lista", "[]");
            try {
                itens = new JSONArray(bruto == null ? "[]" : bruto);
            } catch (Exception e) {
                itens = new JSONArray();
            }
        }

        @Override public void onCreate() { carregar(); }
        @Override public void onDataSetChanged() { carregar(); }
        @Override public void onDestroy() { }

        @Override public int getCount() { return itens.length(); }

        @Override
        public RemoteViews getViewAt(int posicao) {
            RemoteViews linha = new RemoteViews(context.getPackageName(), R.layout.widget_item);
            try {
                JSONObject item = itens.getJSONObject(posicao);
                linha.setTextViewText(R.id.item_descricao, item.optString("descricao", ""));
                linha.setTextViewText(R.id.item_valor, item.optString("valor", ""));
            } catch (Exception e) {
                linha.setTextViewText(R.id.item_descricao, "—");
                linha.setTextViewText(R.id.item_valor, "");
            }

            // Preenche o buraco do PendingIntent-template do provider: sem isso
            // o toque na linha não abre nada.
            linha.setOnClickFillInIntent(R.id.item_raiz, new Intent());
            return linha;
        }

        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int posicao) { return posicao; }
        @Override public boolean hasStableIds() { return true; }
    }
}
