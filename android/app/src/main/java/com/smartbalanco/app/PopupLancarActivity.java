package com.smartbalanco.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;

/**
 * Pop-up de lançamento sobre a tela inicial.
 *
 * É uma Activity com tema de diálogo: aparece como uma janelinha flutuante,
 * com a tela inicial visível e escurecida atrás. Não tem barra, não ocupa a
 * tela toda e não mostra o app — para quem usa, é um pop-up na home.
 *
 * Por que só a ESCOLHA vive aqui: os formulários (manual, documento, IA) são
 * telas web do app. Refazê-los em nativo seria manter duas versões de cada
 * um, que divergiriam na primeira mudança. Então o pop-up decide o caminho e
 * o app abre já na tela certa.
 */
public class PopupLancarActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.popup_lancar);

        ligar(R.id.pop_manual, "novo-manual");
        ligar(R.id.pop_documento, "novo-documento");
        ligar(R.id.pop_arquivar, "novo-arquivar");

        findViewById(R.id.pop_fechar).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
    }

    private void ligar(int idBotao, final String destino) {
        findViewById(idBotao).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent abrir = new Intent(PopupLancarActivity.this, MainActivity.class);
                abrir.setAction(Intent.ACTION_VIEW);
                abrir.setData(Uri.parse("com.smartbalanco.app://" + destino));
                abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                startActivity(abrir);
                finish();
            }
        });
    }

    /** Tocar fora fecha, como todo pop-up. */
    @Override
    public boolean onTouchEvent(android.view.MotionEvent evento) {
        if (evento.getAction() == android.view.MotionEvent.ACTION_OUTSIDE) {
            finish();
            return true;
        }
        return super.onTouchEvent(evento);
    }
}
