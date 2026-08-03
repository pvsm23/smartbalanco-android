package com.smartbalanco.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/**
 * Widget 1x5 vertical: os cinco atalhos do app na tela inicial.
 *
 * Cada ícone abre o app já na tela certa, por um endereço próprio
 * (com.smartbalanco.app://...). Não há pop-up intermediário de propósito:
 * nos atalhos de navegação ele seria só um toque a mais para chegar no
 * mesmo lugar. O "Lançar" é a exceção — esse abre o menu de opções.
 */
public class AcoesWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager gerenciador, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_acoes);

            ligar(context, views, R.id.acao_lancar, "novo", 10);
            ligar(context, views, R.id.acao_ia, "chat", 11);
            ligar(context, views, R.id.acao_busca, "busca", 12);
            ligar(context, views, R.id.acao_aprovacoes, "aprovacoes", 13);
            ligar(context, views, R.id.acao_relatorios, "relatorios", 14);

            gerenciador.updateAppWidget(id, views);
        }
    }

    /**
     * Cada atalho precisa de um requestCode diferente: com o mesmo código, o
     * Android reaproveita o PendingIntent e todos os ícones acabariam abrindo
     * a mesma tela.
     */
    private void ligar(Context context, RemoteViews views, int idBotao, String destino, int codigo) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.setData(Uri.parse("com.smartbalanco.app://" + destino));
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        PendingIntent aoTocar = PendingIntent.getActivity(
            context, codigo, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        views.setOnClickPendingIntent(idBotao, aoTocar);
    }
}
