package com.smartbalanco.app;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.RemoteViews;

/**
 * Widget do microfone: um toque na tela inicial e o app abre já na conversa
 * de lançar falando, sem passar pelo dashboard nem pelo menu de adicionar.
 *
 * Por que o app abre em vez de gravar aqui: o reconhecimento de fala e a
 * conversa com a IA vivem na página, e um widget não roda JavaScript. O que
 * este atalho corta são os três toques até chegar lá — que é justamente o que
 * atrapalha quando se quer lançar uma compra na hora, no caixa.
 *
 * O endereço com.smartbalanco.app://voz é tratado em tratarAtalhoDeTela().
 */
public class VozWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context context, AppWidgetManager gerenciador, int[] ids) {
        for (int id : ids) {
            RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_voz);

            Intent abrir = new Intent(context, MainActivity.class);
            abrir.setAction(Intent.ACTION_VIEW);
            abrir.setData(Uri.parse("com.smartbalanco.app://voz"));
            abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

            PendingIntent aoTocar = PendingIntent.getActivity(
                context, 0, abrir, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            views.setOnClickPendingIntent(R.id.voz_raiz, aoTocar);

            gerenciador.updateAppWidget(id, views);
        }
    }
}
