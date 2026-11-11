### MAY Task Manager v1.0.25
- Remocao do fallback de mascaramento de erros na inspecao root, propagando falhas autenticas e preservando dados validos anteriores na interface.
- Restauracao da coleta otimizada de dumpsys batterystats checkin filtrada por UID para extracao de metricas de hardware pwi e tempos de processo pr.
- Tratamento correto de UID nao resolvido sem atribuicao a root (UID 0) e sem consulta de contadores de sistema.
- Validacao rigorosa de PIDs retornados por pgrep -f atraves da checagem de /proc/pid/cmdline para descartar processos de terceiros.
- Adicao de testes de regressao para timeout, falha de root, preservacao de dados de UI, ausencia de UID e parsing de checkin.
- Configuracao de geracao automatica de Release Beta na branch test para usuarios de teste sem destaque no repositorio.
---
