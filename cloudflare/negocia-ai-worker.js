// NegocIA Trader — Cloudflare Workers AI backend (deployment pending)
const MODEL = "@cf/meta/llama-3.1-8b-instruct";
export default {
  async fetch(request, env) {
    if (request.method !== "POST") return new Response("Method not allowed", {status:405});
    // Do not expose this endpoint publicly before configuring authentication
    // and per-client rate limiting in Cloudflare.
    if (!env.NEGOCIA_API_TOKEN) return new Response("Backend not configured", {status:503});
    const token = request.headers.get("Authorization");
    if (token !== "Bearer " + env.NEGOCIA_API_TOKEN) return new Response("Unauthorized", {status:401});
    const len = Number(request.headers.get("content-length") || 0);
    if (len > 4096) return new Response("Request too large", {status:413});
    let body;
    try { body = await request.json(); } catch { return new Response("Invalid JSON", {status:400}); }
    if (typeof body.message !== "string" || !body.message.trim() || body.message.length > 800) {
      return new Response("Invalid message", {status:400});
    }
    try {
      const result = await env.AI.run(MODEL, {
        messages: [
          {role:"system",content:"Você é Leonan, assistente de voz do NegocIA Trader. Responda em português brasileiro, com naturalidade e objetividade. Não afirme enxergar gráficos, dados de mercado ou preços se não forem fornecidos. Não prometa lucros nem dê sinais de operação sem dados."},
          {role:"user",content:body.message.trim()}
        ],
        max_tokens:256
      });
      return Response.json({reply:result.response || "Não consegui formular uma resposta."});
    } catch {
      return Response.json({error:"Não foi possível consultar a IA agora."},{status:502});
    }
  }
};