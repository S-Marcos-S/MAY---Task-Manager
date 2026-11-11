### MAY Task Manager v1.0.23
- Descarte de lotes atrasados do NetworkStatsManager quando a velocidade real do dispositivo for zero ou abaixo do limiar.
- Distincao explicita entre ausencia real de trafego e falha temporaria de leitura de rede.
- Validacao do resultado de reset avancado no BatteryStatsViewModel, evitando falso sucesso e reportando status detalhado.
- Extensao da identidade de processos (PID e tempo de inicio) ao monitoramento de RAM contra reciclagem de PID.
- Deteccao aprimorada de aplicativos em primeiro plano sem retencao obsoleta de historico ou tarefas recentes.
- Correcao de sintaxe e adicao de testes de regressao para rede, bateria e ciclo de vida de processos.
---
