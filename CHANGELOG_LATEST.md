### MAY Task Manager v1.0.28
- Telemetria de CPU dos processos baseada em contadores diretos de /proc/[pid]/stat (utime + stime) convertidos via CLK_TCK.
- Incorporacao de starttime a chave de processo para imunidade a reciclagem de PIDs pelo kernel.
- Eliminacao de fallback para %CPU acumulado do ps, exibindo indicador indisponivel quando sem medicao confiavel.
- Preservacao de indisponibilidade para impacto energetico e mW, diferenciando ausencia de dados de consumo zero.
- Adicao de testes de regressao para processos inativos, picos de carga, intervalos curtos, CLK_TCK, PID reciclado e impacto energetico.
---
