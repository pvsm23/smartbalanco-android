package com.smartbalanco.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/**
 * Busca o resumo do mês e redesenha o widget, SEM abrir o app.
 *
 * O botão "atualizar" chama isto. Não há tela, não há Activity invisível --
 * abrir uma seria justamente o que o Android bloqueia desde a versão 10, e
 * ver o app piscar na cara para buscar um número é pior que esperar.
 *
 * Como ele se autentica: o app deixa guardadas a URL do servidor e a sessão,
 * no armazenamento privado do próprio app (o mesmo lugar da fila de compras).
 * Sem isso, o serviço não teria como falar com a planilha sozinho. A sessão é
 * uma credencial parada no aparelho -- nenhum outro app a alcança, mas ela
 * existe, e é o preço de atualizar sem abrir nada.
 *
 * Se a sessão não valer mais, o widget NÃO mostra número velho como se fosse
 * novo: ele diz para abrir o app.
 */
public class AtualizarWidgetService extends JobService {

    private static final String TAG = "SmartbalancoWidget";
    private static final int ID_JOB = 7788;

    public static final String CHAVE_URL = "servidorUrl";
    public static final String CHAVE_SESSAO = "sessao";

    /**
     * Agenda a busca. JobScheduler e não Thread solta: o Android mata processo
     * em segundo plano quando quer, e um toque que às vezes funciona é pior
     * que um botão que não existe.
     */
    public static void disparar(Context ctx) {
        JobScheduler js = (JobScheduler) ctx.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (js == null) return;

        JobInfo job = new JobInfo.Builder(ID_JOB, new ComponentName(ctx, AtualizarWidgetService.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setOverrideDeadline(0)          // agora, não "quando der"
            .build();
        js.schedule(job);
    }

    @Override
    public boolean onStartJob(final JobParameters params) {
        new Thread(new Runnable() {
            @Override public void run() {
                boolean ok = buscar(getApplicationContext());
                CalendarioWidget.redesenharTodos(getApplicationContext());
                jobFinished(params, !ok);   // falhou: o Android tenta de novo
            }
        }).start();
        return true;   // ainda trabalhando
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    private boolean buscar(Context ctx) {
        SharedPreferences wprefs = ctx.getSharedPreferences(CalendarioWidget.PREFS, Context.MODE_PRIVATE);
        String url = wprefs.getString(CHAVE_URL, "");
        String sessao = wprefs.getString(CHAVE_SESSAO, "");

        if (url.isEmpty() || sessao.isEmpty()) {
            Log.w(TAG, "Sem credencial guardada; abra o app uma vez.");
            return false;
        }

        HttpURLConnection con = null;
        try {
            String completa = url + "?acao=resumoWidget&sessao=" + URLEncoder.encode(sessao, "UTF-8");
            con = (HttpURLConnection) new URL(completa).openConnection();
            con.setInstanceFollowRedirects(true);   // o Apps Script redireciona
            con.setConnectTimeout(15000);
            con.setReadTimeout(20000);

            BufferedReader br = new BufferedReader(new InputStreamReader(con.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String linha;
            while ((linha = br.readLine()) != null) sb.append(linha);
            br.close();

            JSONObject r = new JSONObject(sb.toString());
            if (!r.optBoolean("ok", false)) {
                Log.w(TAG, "Servidor recusou: " + r.optString("mensagem"));
                // Sessão morta: apaga, para o widget parar de tentar e passar a
                // pedir que o app seja aberto.
                if ("NAO_AUTORIZADO".equals(r.optString("erro"))) {
                    wprefs.edit().remove(CHAVE_SESSAO).apply();
                }
                return false;
            }

            wprefs.edit()
                .putString(CalendarioWidget.CHAVE_RESUMO, sb.toString())
                .putLong(CalendarioWidget.CHAVE_QUANDO, System.currentTimeMillis())
                .apply();

            Log.i(TAG, "Resumo atualizado.");
            return true;

        } catch (Exception e) {
            Log.w(TAG, "Falha ao buscar: " + e.getMessage());
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }
}
