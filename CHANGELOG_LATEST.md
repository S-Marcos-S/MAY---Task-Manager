### MAY Task Manager v1.0.21
- Eliminacao de condicao de corrida na captura de rede com sessao imutavel e sincronizacao via CompletableDeferred.
- Medicao diferencial real de CPU baseada em tempo acumulado do kernel com identificacao de reciclagem de PID.
- Deteccao de anomalia de repouso com diferenciacao rigorosa entre contexto de servico e wakelocks comprovados.
- Medicao direta de RSS em KB na analise de memoria sob pressao e historico conservador para vazamentos.
- Identificacao transparente de UIDs compartilhados e deteccao de dados obsoletos (stale) no monitor de rede.
- Migracao retrocompativel de historico legado com classificacao conservadora de confianca e sondagens ativas em diagnosticos.
---
