<p align="center">
  <img src="docs/icon.png" width="128" height="128" alt="MAY Task Manager Icon" />
</p>

<h1 align="center">MAY - Task Manager</h1>

<p align="center">
  <strong>Make Android Yours: Gerenciador avançado de tarefas, telemetria de hardware em tempo real, diagnóstico de bateria, monitoramento de rede e controle profundo de processos para Android com suporte a Superuser / Root.</strong>
</p>

<p align="center">
  <a href="https://github.com/S-Marcos-S/MAY---Task-Manager/releases/latest"><img src="https://img.shields.io/github/v/release/S-Marcos-S/MAY---Task-Manager?style=for-the-badge&color=7F52FF&logo=github&label=Vers%C3%A3o" alt="Versão Mais Recente" /></a>
  <a href="https://github.com/S-Marcos-S/MAY---Task-Manager/releases"><img src="https://img.shields.io/github/downloads/S-Marcos-S/MAY---Task-Manager/total?style=for-the-badge&color=00897B&logo=github&label=Downloads" alt="Total de Downloads" /></a>
  <a href="https://github.com/S-Marcos-S/MAY---Task-Manager/actions/workflows/build.yml"><img src="https://img.shields.io/github/actions/workflow/status/S-Marcos-S/MAY---Task-Manager/build.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white&label=Build" alt="Status da Build" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/Licen%C3%A7a-GPL--3.0-EA4335?style=for-the-badge&logo=gnu" alt="Licença GPL v3" /></a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Kotlin-2.0+-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white" alt="Jetpack Compose" />
  <img src="https://img.shields.io/badge/Design-Liquid%20Glass%20%26%20Monet-FF6F00?style=for-the-badge&logo=materialdesign&logoColor=white" alt="Design System" />
  <img src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 8.0+" />
  <img src="https://img.shields.io/badge/Alvo-Android%2015%20(API%2035)-00C853?style=for-the-badge&logo=android&logoColor=white" alt="Target SDK 35" />
  <img src="https://img.shields.io/badge/Superuser-Root%20Opcional%20e%20Avançado-00BCD4?style=for-the-badge&logo=android" alt="Superuser / Root" />
  <img src="https://img.shields.io/badge/Otimização-R8%20Shrinking-673AB7?style=for-the-badge" alt="R8 Shrinking" />
</p>

<p align="center">
  <a href="#visao-geral">Visão Geral</a> •
  <a href="#destaques-e-recursos">Destaques</a> •
  <a href="#modulos-do-sistema">Módulos</a> •
  <a href="#recursos-para-superusuario">Superusuário</a> •
  <a href="#temas-e-liquid-glass">Temas</a> •
  <a href="#sistema-de-atualizacoes-e-canal-beta">Atualizações & Beta</a> •
  <a href="#download">Download</a> •
  <a href="#licenca">Licença</a>
</p>

---

## Visao Geral

O **MAY Task Manager** (Make Android Yours) é um aplicativo dedicado ao monitoramento, análise cirúrgica e otimização avançada do sistema Android. Construído com tecnologias modernas (Kotlin 2.0+, Jetpack Compose, Coroutines, Shaders AGSL e arquitetura reativa StateFlow), o MAY combina telemetria de nível de sistema operacional com uma interface estritamente minimalista, compacta e responsiva.

O aplicativo funciona tanto em dispositivos convencionais sem privilégios elevados quanto em aparelhos rooteados (Superuser / su), liberando controles diretos sobre o kernel, memória RAM, processos zumbis, wakelocks, conexões de soquete e limites de desempenho.

---

## Destaques e Recursos

- **Interface Minimalista e Compacta:** Todos os painéis, métricas e gráficos foram desenvolvidos para caber com perfeição e sem quebra de linhas na tela, sem textos prolixos ou botões superdimensionados.
- **Telemetria de Hardware em Tempo Real:** Frequências individuais de todos os núcleos da CPU, governadores do kernel, carga percentual de CPU, uso de RAM, Swap/zRAM, temperatura de sensores térmicos e estimativas energéticas.
- **Gráficos Interativos:** Histórico com atualização contínua e amostragem visual de carga de processamento, consumo de memória, taxas de transferência de rede e curvas de descarga da bateria.
- **Inspetor Cirúrgico de Processos:** Análise por processo de memória PSS, RSS, threads ativas, bibliotecas nativas `.so` mapeadas e conexões abertas de rede (TCP/UDP).
- **Encerramento Forçado e Prioridades:** Finalização imediata de processos rebeldes (`kill -9`, `am force-stop`), limpeza de cache e gerenciamento de permissões restritas.
- **Monitor de Velocidade de Rede com Notificação Persistente:** Notificação opcional em segundo plano exibindo taxas de download/upload ao vivo, identificação dos aplicativos que mais consomem banda e limites mínimos configuráveis para economia de CPU.
- **Diagnóstico Profundo de Bateria:** Integração com `dumpsys batterystats`, rastreador de ciclos de carga e descarga, cálculo de tempo de tela ligada e tela desligada, além de análise de sono profundo (*Deep Sleep*).
- **Controle de Enforce Doze:** Acionamento forçado do modo Doze do Android assim que a tela é desligada, com opção de suspender sensores de movimento para máxima autonomia de bateria.
- **Terminal Integrado e Visualizador de Logcat:** Acesso a terminal shell interativo com suporte a comandos root, acompanhado de janela de visualização em tempo real de logs do sistema e de falhas (*Crash Manager*) com filtros por pacote, tag e nível de severidade.
- **Detector Inteligente de Anomalias em Segundo Plano:** Serviço de vigia (*BackgroundAnomalyWatcher*) que detecta picos anômalos de consumo de CPU, vazamentos de memória e despertares excessivos em repouso.
- **Design System com Liquid Glass e Monet:** Suporte aos temas Material You (Monet Dinâmico), Liquid Glass translúcido com dispersão cromática e refração em tempo real via shaders AGSL, OLED Preto Puro, Modern Dark e Light Clean.
- **Seletor de Temas Padronizado:** Interface com pontos seletivos animados e nome correspondente, idêntica à ergonomia refinada do projeto.
- **Atualizador Automático Embutido:** Consulta instantânea à API do GitHub Releases ao entrar na tela de configurações, suporte a instalação silenciosa via Superuser e canal dedicado de testes Beta.

---

## Modulos do Sistema

### 1. Processos (Processes)
- Listagem segmentada em tempo real: Aplicativos em Primeiro Plano (*Foreground*), Serviços em Segundo Plano (*Background*) e Processos de Sistema (*System*).
- Busca instantânea por nome ou pacote.
- Inspeção em um toque com detalhes de PID, UID, uso de memória PSS, tempo de execução e estado de ciclo de vida.
- Ações rápidas de encerramento, abertura de configurações do sistema e exportação de diagnóstico.

### 2. Desempenho (Performance)
- Monitor em tempo real de núcleos da CPU (frequências atuais, limites mínimos e máximos por cluster).
- Gráficos contínuos de CPU, memória RAM, taxa de escrita/leitura em armazenamento e taxas de transferência de rede.
- Sensores térmicos por zona do hardware (bateria, CPU, modem, placa-mãe).

### 3. Historico Diario (Daily History)
- Consolidação diária de métricas de uso: tempo de tela, tempo em repouso, ciclos de carga, consumo agregado de bateria e abertura de aplicativos.
- Gráficos de tráfego de dados por hora e registro de quebras de sono profundo causadas por processos em segundo plano.

### 4. Ciclos de Bateria (Cycles)
- Rastreamento dos ciclos de carga e descarga com cálculo de porcentagem consumida, duração de cada evento e taxa horária de drenagem (%/h).
- Filtros por tipo de ciclo (descarga, carga ou histórico completo).

### 5. Monitor de Rede (Network)
- Estatísticas detalhadas de consumo de dados por interface (Wi-Fi e Dados Móveis).
- Painel de configuração do serviço de velocidade em tempo real (`SpeedMonitorService`):
  - Limiar de ativação (B/s, KB/s, MB/s) para evitar notificações desnecessárias quando inativo.
  - Intervalos de amostragem configuráveis de 1 a 10 segundos.
  - Identificação de pacotes ativos em segundo plano gerando tráfego.

### 6. Diagnostico de Bateria e Doze (Battery)
- Análise de consumo detalhada via `dumpsys batterystats`.
- Lista de wakelocks do kernel e wakelocks parciais de aplicativos.
- Painel Enforce Doze: força a suspensão profunda imediatamente após o bloqueio de tela.

### 7. Detector de Anomalias (Background Watcher)
- Monitor de repouso configurável para alertar o usuário quando algum app exceder o limiar de consumo de processamento ou acordar o dispositivo excessivamente.
- Histórico de incidentes com registro de hora, aplicativo ofensor, severidade e métrica excedida.

---

## Recursos para Superusuario

O MAY Task Manager é totalmente funcional em dispositivos sem root (utilizando APIs padrão do Android, TrafficStats, UsageStats e NetworkStatsManager). Contudo, a presença de Superuser habilita um conjunto avançado de operações de baixo nível:

- Leitura direta de interfaces `/proc`, `/sys/class/thermal` e estatísticas do kernel.
- Encerramento definitivo e forçado de processos de sistema protegidos.
- Concessão automática de permissões protegidas de depuração (`DUMP`, `PACKAGE_USAGE_STATS`, `BATTERY_STATS`).
- Instalação e atualização silenciosa de APKs sem necessidade de interação manual com o instalador do sistema.
- Execução direta de comandos shell e scripts de diagnóstico pelo terminal interno.
- Controle direto de estados de energia do kernel e ativação instantânea do modo Doze profundo.

---

## Temas e Liquid Glass

A tela de configurações do MAY conta com seletor de temas compacto com pontos seletores coloridos e tipografia adaptativa:

- **Liquid Glass:** Efeito de vidro líquido translúcido renderizado através de AGSL (*Android Graphics Shading Language*), integrando refração, aberração cromática e desfoque suave de acordo com o fundo da tela.
- **Monet Dinamico:** Extração inteligente da paleta tonal do wallpaper do usuário através da biblioteca Material You.
- **OLED Preto Puro:** Fundo `#000000` absoluto com elementos de alto contraste para economia máxima em telas AMOLED.
- **Modern Dark:** Tons modernos de cinza escuro e superfícies suaves para uso noturno confortável.
- **Light Clean:** Tema claro com alto contraste, excelente legibilidade e visual limpo sob luz solar direta.

---

## Sistema de Atualizacoes e Canal Beta

O MAY Task Manager inclui um mecanismo integrado de entrega contínua através do GitHub Releases:

- **Verificacao na Entrada:** Ao abrir a tela de configurações, o aplicativo consulta a versão mais recente publicada no repositório.
- **Canal de Versoes Beta:** O usuário pode ativar a opção **Testador de Versões Beta** nas configurações para receber versões preliminares e experimentais antes do lançamento público geral.
- **Destaque de Releases Estaveis:** As versões de teste utilizam identificação de pré-lançamento (*prerelease: true* e *make_latest: false*), mantendo a versão estável oficial sempre fixada como destaque principal no repositório.
- **Instalacao Silenciosa:** Em dispositivos com Superuser, o processo de download, validação de integridade e instalação do APK é realizado de maneira totalmente silenciosa e transparente.

---

## Requisitos de Sistema

- **Versao Minima do Android:** Android 8.0 (API 26) Oreo ou superior.
- **Versao Alvo:** Android 15 (API 35).
- **Arquiteturas Suportadas:** arm64-v8a, armeabi-v7a, x86, x86_64.
- **Privilegios de Superusuario:** Opcional (habilita telemetria avançada, inspeção irrestrita e comandos de kernel).

---

## Download

Os arquivos de instalação (APK) oficiais podem ser obtidos na aba de lançamentos do projeto:

- **[Releases Oficiais no GitHub](https://github.com/S-Marcos-S/MAY---Task-Manager/releases)**
- **[Ultima Versao Estavel](https://github.com/S-Marcos-S/MAY---Task-Manager/releases/latest)**

---

## Compilacao e Otimizacao

O projeto é configurado com compilação automatizada via GitHub Actions com otimizações agressivas de compacidade:

- **Compilador:** Kotlin 2.0+ com plugin Compose.
- **Minificacao:** R8 habilitado com ofuscação, remoção de classes e métodos inacessíveis, além de empacotamento compacto de pacotes.
- **Shrinking de Recursos:** Remoção automática de recursos não utilizados para gerar um APK de tamanho mínimo e execução veloz.

---

## Licenca

Este projeto é software livre e de código aberto, distribuído sob os termos da licença **GNU General Public License v3.0 (GPL-3.0)**. Consulte o arquivo [LICENSE](LICENSE) para obter o texto completo da licença.
