package com.smartbalanco.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/**
 * Widget "+": atalho da tela inicial que cai direto no menu de lançamento
 * (IA, anexo, manual), sem passar pelo dashboard.
 *
 * Limite honesto: as três opções são telas do app (a leitura por IA precisa da
 * câmera e do envio ao servidor), então o app abre — o que este atalho evita é
 * a navegação até lá. Ele abre pelo endereço com.smartbalanco.app://novo, e o
 * app, ao receber, escancara o menu de adicionar já na entrada.
 */
public class NovoLancamentoWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager gerenciador, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_novo);

            Intent abrir = new Intent(context, PopupLancarActivity.class);
            abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent aoTocar = PendingIntent.getActivity(
                context, 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            views.setOnClickPendingIntent(R.id.novo_raiz, aoTocar);

            gerenciador.updateAppWidget(id, views);
        }
    }
}
