package com.smartbalanco.app;

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
        call.resolve();
    }
}
