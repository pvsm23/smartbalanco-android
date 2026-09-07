package com.smartbalanco.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import org.json.JSONArray;

/**
 * Ponte entre a fila de compras capturadas (lado nativo) e o app (a página).
 *
 * A leitura de notificações vive num serviço do Android, fora do alcance do
 * JavaScript. Este plugin é o único caminho por onde esses dados chegam à
 * tela — e ele só LÊ e LIMPA: nada aqui envia nada para fora do aparelho.
 */
@CapacitorPlugin(name = "NotificacoesBanco")
public class NotificacoesPlugin extends Plugin {

    /**
     * A permissão de ler notificações não pode ser pedida por diálogo: o
     * Android exige que o usuário a conceda numa tela própria das
     * Configurações. Aqui só se descobre se já foi concedida.
     */
    @PluginMethod
    public void temPermissao(PluginCall call) {
        String ativos = Settings.Secure.getString(
            getContext().getContentResolver(), "enabled_notification_listeners");

        boolean tem = ativos != null && ativos.contains(getContext().getPackageName());

        JSObject r = new JSObject();
        r.put("tem", tem);
        call.resolve(r);
    }

    /** Abre a tela de Configurações onde a permissão é concedida. */
    @PluginMethod
    public void pedirPermissao(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Não consegui abrir as configurações: " + e.getMessage());
        }
    }

    /** Devolve as compras capturadas, sem apagar. */
    @PluginMethod
    public void listar(PluginCall call) {
        try {
            SharedPreferences prefs = getContext()
                .getSharedPreferences(LeitorNotificacoes.PREFS, Context.MODE_PRIVATE);

            JSONArray fila = new JSONArray(prefs.getString(LeitorNotificacoes.CHAVE_FILA, "[]"));

            JSObject r = new JSObject();
            r.put("itens", JSArray.from(fila));
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Falha ao ler a fila: " + e.getMessage());
        }
    }

    /**
     * Limpa a fila. Só é chamado DEPOIS que o app confirmou o envio para
     * Aprovações — limpar antes perderia a compra se a rede caísse no meio.
     */
    @PluginMethod
    public void limpar(PluginCall call) {
        try {
            getContext()
                .getSharedPreferences(LeitorNotificacoes.PREFS, Context.MODE_PRIVATE)
                .edit().putString(LeitorNotificacoes.CHAVE_FILA, "[]").apply();

            // A fila foi para Aprovações: o resumo na barra e o acumulado da
            // hora cheia perdem o sentido junto com ela.
            LeitorNotificacoes.esquecerPendencias(getContext());
            call.resolve();
        } catch (Exception e) {
            call.reject("Falha ao limpar: " + e.getMessage());
        }
    }
}
