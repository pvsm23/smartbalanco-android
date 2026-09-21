package com.smartbalanco.app;

import android.content.Context;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Ponte mínima para o app pedir o redesenho do widget.
 *
 * Só o lado nativo consegue falar com o AppWidgetManager. Sem isso, o widget
 * ficaria preso ao ciclo de 30 minutos do Android e mostraria dado velho logo
 * depois de o usuário liquidar uma conta no app.
 */
@CapacitorPlugin(name = "Widget")
public class WidgetPlugin extends Plugin {

    @PluginMethod
    public void atualizar(PluginCall call) {
        SmartbalancoWidget.atualizarTodos(getContext());
        AgendaWidget.atualizarTodos(getContext());
        CalendarioWidget.redesenharTodos(getContext());
        call.resolve();
    }

    /**
     * Guarda o resumo do mês que o widget de calendário desenha.
     *
     * O widget não fala com a planilha: ele desenha o que ficou guardado aqui.
     * Chamado sempre que o app carrega dados novos, para o widget não ficar
     * preso ao ciclo de meia hora do Android.
     */
    @PluginMethod
    public void guardarResumo(PluginCall call) {
        String json = call.getString("resumo", "");
        if (json == null || json.isEmpty()) {
            call.reject("Resumo vazio.");
            return;
        }
        getContext().getSharedPreferences(CalendarioWidget.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(CalendarioWidget.CHAVE_RESUMO, json)
            .putLong(CalendarioWidget.CHAVE_QUANDO, System.currentTimeMillis())
            .apply();

        CalendarioWidget.redesenharTodos(getContext());
        call.resolve();
    }

    /**
     * Guarda a credencial que o botão "atualizar" usa para buscar sozinho.
     *
     * É o que permite atualizar sem abrir o app. Fica no armazenamento privado
     * — nenhum outro aplicativo alcança — mas é uma sessão parada no aparelho,
     * e por isso só é gravada quando há uma sessão válida de verdade.
     */
    @PluginMethod
    public void guardarCredencial(PluginCall call) {
        String url = call.getString("url", "");
        String sessao = call.getString("sessao", "");

        if (url == null || url.isEmpty() || sessao == null || sessao.isEmpty()) {
            // Sem sessão válida, APAGA o que houver: credencial velha faria o
            // serviço tentar em silêncio e falhar para sempre.
            getContext().getSharedPreferences(CalendarioWidget.PREFS, Context.MODE_PRIVATE)
                .edit().remove(AtualizarWidgetService.CHAVE_SESSAO).apply();
            call.resolve();
            return;
        }

        getContext().getSharedPreferences(CalendarioWidget.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(AtualizarWidgetService.CHAVE_URL, url)
            .putString(AtualizarWidgetService.CHAVE_SESSAO, sessao)
            .apply();
        call.resolve();
    }
}
