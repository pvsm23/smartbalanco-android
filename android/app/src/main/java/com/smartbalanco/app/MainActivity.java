package com.smartbalanco.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Base64;

import com.getcapacitor.BridgeActivity;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class MainActivity extends BridgeActivity {

    private static final String ARQUIVO_PREFS = "CapacitorStorage";
    // Acima disso o base64 estoura o que vale a pena guardar em preferências
    // (e o servidor recusa o envio de qualquer forma).
    private static final int LIMITE_BYTES = 6 * 1024 * 1024;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WidgetPlugin.class);
        super.onCreate(savedInstanceState);
        receberCompartilhado(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receberCompartilhado(intent);
    }

    /**
     * Recebe imagem ou PDF compartilhado de outro app.
     *
     * O lado web não enxerga o Intent, então o arquivo é lido aqui, virado
     * base64 e deixado no mesmo SharedPreferences que o plugin Preferences
     * usa — de onde o JavaScript pega e abre as opções de lançamento.
     */
    private void receberCompartilhado(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;

        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) return;

        try {
            String mime = intent.getType();
            if (mime == null) mime = getContentResolver().getType(uri);
            if (mime == null) mime = "application/octet-stream";

            InputStream entrada = getContentResolver().openInputStream(uri);
            if (entrada == null) return;

            ByteArrayOutputStream saida = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int lidos;
            int total = 0;

            while ((lidos = entrada.read(buffer)) != -1) {
                total += lidos;
                if (total > LIMITE_BYTES) { entrada.close(); return; }
                saida.write(buffer, 0, lidos);
            }
            entrada.close();

            // NO_WRAP: sem quebras de linha, senão o base64 chega inválido no JS
            String base64 = Base64.encodeToString(saida.toByteArray(), Base64.NO_WRAP);

            SharedPreferences.Editor editor =
                getSharedPreferences(ARQUIVO_PREFS, MODE_PRIVATE).edit();
            editor.putString("doc_compartilhado", base64);
            editor.putString("doc_compartilhado_mime", mime);
            editor.putString("doc_compartilhado_nome", nomeDoArquivo(uri));
            editor.apply();

        } catch (Exception e) {
            // Compartilhar é conveniência: falhar aqui não pode derrubar o app.
            android.util.Log.w("Smartintegrado", "Não consegui ler o compartilhado", e);
        }
    }

    private String nomeDoArquivo(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                String nome = null;
                if (idx >= 0 && c.moveToFirst()) nome = c.getString(idx);
                c.close();
                if (nome != null) return nome;
            }
        } catch (Exception ignorado) { }
        return "documento";
    }
}
