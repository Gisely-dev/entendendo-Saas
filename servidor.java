import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Servidor do site de estudo "Como funciona um SaaS?".
 * Usa apenas o JDK (com.sun.net.httpserver): nada para instalar.
 *
 * Faz duas coisas:
 *  1) entrega os arquivos da pasta /public (HTML, CSS, JS);
 *  2) oferece uma pequena API em /api/... que o JavaScript chama com fetch().
 */
public class Servidor {

    // ---------- Dados do quiz (ficam no servidor, por isso a resposta certa não vai para o navegador) ----------
    record Pergunta(int id, String texto, List<String> opcoes, int certa, String explicacao) {}

    static final List<Pergunta> PERGUNTAS = List.of(
        new Pergunta(1, "O que significa a sigla SaaS?",
            List.of("Software as a Service", "System and a Server", "Secure and a Safe", "Storage as a Site"), 0,
            "SaaS = Software as a Service, ou software como serviço: você usa pela internet em vez de instalar."),
        new Pergunta(2, "Qual é a principal diferença entre software tradicional e SaaS?",
            List.of("O SaaS é sempre gratuito", "No SaaS você assina e acessa pela internet", "O SaaS só funciona sem internet", "Não existe diferença"), 1,
            "No modelo tradicional você compra a licença e instala. No SaaS você assina e acessa pelo navegador ou app."),
        new Pergunta(3, "Onde o código de um SaaS normalmente roda?",
            List.of("No seu celular", "No seu computador", "Em servidores na nuvem", "Dentro do navegador, sem servidor"), 2,
            "O código principal roda em servidores na nuvem. O seu aparelho só mostra a tela e envia pedidos."),
        new Pergunta(4, "O que é multi-tenant (multi-inquilino)?",
            List.of("Um software para cada cliente", "Um só software atendendo vários clientes com dados separados", "Um banco de dados sem login", "Um tipo de servidor físico"), 1,
            "Como um prédio: a estrutura é a mesma para todos, mas cada morador só entra no próprio apartamento."),
        new Pergunta(5, "O que significa MRR em um SaaS?",
            List.of("Máximo Retorno Registrado", "Receita Recorrente Mensal", "Modelo de Rede Remota", "Média de Requisições por Resposta"), 1,
            "MRR = Monthly Recurring Revenue: o total que as assinaturas rendem todo mês."),
        new Pergunta(6, "O que é churn?",
            List.of("Quando um cliente cancela a assinatura", "Um erro no servidor", "Um plano gratuito", "A atualização automática do sistema"), 0,
            "Churn é a taxa de clientes que cancelam. Quanto menor, melhor para a receita do SaaS."),
        new Pergunta(7, "Qual parte do SaaS guarda as informações dos usuários?",
            List.of("Front-end", "Banco de dados", "Navegador", "CSS"), 1,
            "O banco de dados guarda cadastros, arquivos e histórico. O back-end conversa com ele."),
        new Pergunta(8, "Qual destes é um exemplo de SaaS?",
            List.of("Um jogo instalado do disco", "Uma calculadora antiga offline", "Notion", "Um editor de texto baixado sem conta"), 2,
            "Notion, Canva, Figma e Slack são SaaS: você usa pela internet e paga uma assinatura (ou usa o plano grátis).")
    );

    // ---------- Dados do exemplo multi-tenant ----------
    static final Map<String, String> DADOS_EMPRESAS = Map.of(
        "padaria", "{\"empresa\":\"Padaria Pão Quente\",\"plano\":\"Pro\",\"usuarios\":4,\"itens\":[[\"Pão francês\",\"320 un\"],[\"Bolo de cenoura\",\"12 un\"],[\"Café\",\"48 un\"]],\"faturamento\":\"R$ 8.400\"}",
        "academia", "{\"empresa\":\"Academia Corpo em Dia\",\"plano\":\"Empresas\",\"usuarios\":12,\"itens\":[[\"Alunos ativos\",\"215\"],[\"Planos vencendo\",\"18\"],[\"Aulas hoje\",\"9\"]],\"faturamento\":\"R$ 23.900\"}",
        "clinica", "{\"empresa\":\"Clínica Bem Estar\",\"plano\":\"Empresas\",\"usuarios\":7,\"itens\":[[\"Consultas hoje\",\"26\"],[\"Pacientes\",\"1.140\"],[\"Retornos\",\"11\"]],\"faturamento\":\"R$ 31.250\"}"
    );

    // ---------- Progresso de estudo (em memória, salvo em arquivo) ----------
    static final Set<String> PAGINAS_VISTAS = ConcurrentHashMap.newKeySet();
    static final Path ARQUIVO_PROGRESSO = Path.of("progresso.txt");
    static final Path PASTA_PUBLICA = Path.of("public").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {
        int porta = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        if (Files.exists(ARQUIVO_PROGRESSO)) {
            PAGINAS_VISTAS.addAll(Files.readAllLines(ARQUIVO_PROGRESSO));
        }

        HttpServer servidor = HttpServer.create(new InetSocketAddress(porta), 0);
        servidor.createContext("/", Servidor::arquivosEstaticos);
        servidor.createContext("/api/quiz", Servidor::quiz);
        servidor.createContext("/api/responder", Servidor::responder);
        servidor.createContext("/api/dados", Servidor::dadosEmpresa);
        servidor.createContext("/api/mrr", Servidor::projecaoMrr);
        servidor.createContext("/api/progresso", Servidor::progresso);
        servidor.start();
        System.out.println("Site no ar: http://localhost:" + porta);
    }

    // =====================================================================
    //  API
    // =====================================================================

    /** GET /api/quiz -> lista de perguntas SEM a resposta certa. */
    static void quiz(HttpExchange ex) throws IOException {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < PERGUNTAS.size(); i++) {
            Pergunta p = PERGUNTAS.get(i);
            if (i > 0) json.append(",");
            json.append("{\"id\":").append(p.id())
                .append(",\"texto\":").append(aspas(p.texto()))
                .append(",\"opcoes\":[");
            for (int j = 0; j < p.opcoes().size(); j++) {
                if (j > 0) json.append(",");
                json.append(aspas(p.opcoes().get(j)));
            }
            json.append("]}");
        }
        json.append("]");
        enviarJson(ex, 200, json.toString());
    }

    /** POST /api/responder (id=1&opcao=0) -> confere a resposta no servidor. */
    static void responder(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) { enviarJson(ex, 405, "{\"erro\":\"use POST\"}"); return; }
        Map<String, String> form = lerCorpo(ex);
        try {
            int id = Integer.parseInt(form.get("id"));
            int opcao = Integer.parseInt(form.get("opcao"));
            Pergunta p = PERGUNTAS.stream().filter(q -> q.id() == id).findFirst().orElseThrow();
            String json = "{\"correta\":" + (opcao == p.certa()) + ",\"certa\":" + p.certa()
                + ",\"explicacao\":" + aspas(p.explicacao()) + "}";
            enviarJson(ex, 200, json);
        } catch (Exception e) {
            enviarJson(ex, 400, "{\"erro\":\"pedido inválido\"}");
        }
    }

    /**
     * GET /api/dados?empresa=padaria&como=padaria
     * Simula o isolamento multi-tenant: só entrega os dados se "como" (quem está logado)
     * for a mesma empresa pedida. Caso contrário devolve 403 (proibido).
     */
    static void dadosEmpresa(HttpExchange ex) throws IOException {
        Map<String, String> q = lerConsulta(ex);
        String empresa = q.getOrDefault("empresa", "");
        String como = q.getOrDefault("como", "");
        if (!DADOS_EMPRESAS.containsKey(empresa)) { enviarJson(ex, 404, "{\"erro\":\"empresa não encontrada\"}"); return; }
        if (!empresa.equals(como)) {
            enviarJson(ex, 403, "{\"erro\":\"Acesso negado: você está logado como '" + escapar(como)
                + "' e não pode ver os dados de '" + escapar(empresa) + "'.\"}");
            return;
        }
        enviarJson(ex, 200, DADOS_EMPRESAS.get(empresa));
    }

    /**
     * GET /api/mrr?clientes=100&preco=49&churn=5&novos=20
     * Projeta 12 meses: clientes(m+1) = clientes(m) - cancelamentos + novos.
     */
    static void projecaoMrr(HttpExchange ex) throws IOException {
        Map<String, String> q = lerConsulta(ex);
        try {
            double clientes = Double.parseDouble(q.getOrDefault("clientes", "100"));
            double preco = Double.parseDouble(q.getOrDefault("preco", "49"));
            double churn = Double.parseDouble(q.getOrDefault("churn", "5")) / 100.0;
            double novos = Double.parseDouble(q.getOrDefault("novos", "20"));

            StringBuilder json = new StringBuilder("{\"meses\":[");
            double totalAcumulado = 0;
            for (int mes = 1; mes <= 12; mes++) {
                double mrr = clientes * preco;
                totalAcumulado += mrr;
                if (mes > 1) json.append(",");
                json.append(String.format(java.util.Locale.US,
                    "{\"mes\":%d,\"clientes\":%.0f,\"mrr\":%.2f}", mes, clientes, mrr));
                clientes = clientes - clientes * churn + novos;
            }
            json.append(String.format(java.util.Locale.US, "],\"receitaAno\":%.2f}", totalAcumulado));
            enviarJson(ex, 200, json.toString());
        } catch (NumberFormatException e) {
            enviarJson(ex, 400, "{\"erro\":\"números inválidos\"}");
        }
    }

    /** GET /api/progresso -> páginas já visitadas. POST (pagina=quiz) -> marca como visitada. */
    static void progresso(HttpExchange ex) throws IOException {
        if (ex.getRequestMethod().equals("POST")) {
            String pagina = lerCorpo(ex).getOrDefault("pagina", "");
            if (!pagina.isBlank() && pagina.length() < 40) {
                PAGINAS_VISTAS.add(pagina);
                Files.write(ARQUIVO_PROGRESSO, new TreeSet<>(PAGINAS_VISTAS));
            }
        }
        List<String> lista = new ArrayList<>(new TreeSet<>(PAGINAS_VISTAS));
        StringBuilder json = new StringBuilder("{\"vistas\":[");
        for (int i = 0; i < lista.size(); i++) {
            if (i > 0) json.append(",");
            json.append(aspas(lista.get(i)));
        }
        json.append("]}");
        enviarJson(ex, 200, json.toString());
    }

    // =====================================================================
    //  Arquivos estáticos
    // =====================================================================

    static void arquivosEstaticos(HttpExchange ex) throws IOException {
        String caminho = ex.getRequestURI().getPath();
        if (caminho.equals("/")) caminho = "/index.html";

        Path arquivo = PASTA_PUBLICA.resolve(caminho.substring(1)).normalize();
        // segurança: impede acessar arquivos fora da pasta public (ex.: /../Servidor.java)
        if (!arquivo.startsWith(PASTA_PUBLICA) || !Files.isRegularFile(arquivo)) {
            byte[] msg = "404 - página não encontrada".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(msg); }
            return;
        }
        byte[] bytes = Files.readAllBytes(arquivo);
        ex.getResponseHeaders().set("Content-Type", tipo(arquivo.toString()));
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String tipo(String nome) {
        if (nome.endsWith(".html")) return "text/html; charset=utf-8";
        if (nome.endsWith(".css")) return "text/css; charset=utf-8";
        if (nome.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (nome.endsWith(".svg")) return "image/svg+xml";
        if (nome.endsWith(".png")) return "image/png";
        return "application/octet-stream";
    }

    // =====================================================================
    //  Utilitários
    // =====================================================================

    static void enviarJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static Map<String, String> lerConsulta(HttpExchange ex) {
        return paraMapa(ex.getRequestURI().getRawQuery());
    }

    static Map<String, String> lerCorpo(HttpExchange ex) throws IOException {
        return paraMapa(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    /** Converte "a=1&b=2" em Map. */
    static Map<String, String> paraMapa(String texto) {
        Map<String, String> mapa = new HashMap<>();
        if (texto == null || texto.isBlank()) return mapa;
        for (String par : texto.split("&")) {
            String[] kv = par.split("=", 2);
            String chave = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String valor = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            mapa.put(chave, valor);
        }
        return mapa;
    }

    static String aspas(String s) { return "\"" + escapar(s) + "\""; }

    static String escapar(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
