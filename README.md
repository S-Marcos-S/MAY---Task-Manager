<p align="center">
  <img src="docs/icon.png" width="128" height="128" alt="MAY Task Manager Icon" />
</p>

<h1 align="center">MAY - Task Manager</h1>

<p align="center">
  <strong>Make Android Yours: Gerenciador avançado de tarefas, telemetria de hardware em tempo real, diagnóstico de bateria, monitoramento de rede e controle profundo de processos para Android via Superusuário / Root.</strong>
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
  <a href="#telas-do-aplicativo">Telas</a> •
  <a href="#modulos-do-sistema">Módulos</a> •
  <a href="#recursos-para-superusuario">Superusuário</a> •
  <a href="#temas-e-liquid-glass">Temas</a> •
  <a href="#sistema-de-atualizacoes-e-canal-beta">Atualizações & Beta</a> •
  <a href="#download">Download</a> •
  <a href="#creditos">Créditos</a> •
  <a href="#licenca">Licença</a>
</p>

---

## Visao Geral

O **MAY Task Manager** (Make Android Yours) é um aplicativo dedicado ao monitoramento, análise e otimização avançada do sistema Android. Tudo que você esperaria de um gerenciador de tarefas de desktop, agora no seu smartphone.

O aplicativo funciona é focado em aparelhos rooteados (Superuser / su), liberando controles diretos sobre o kernel.

---

## Telas do Aplicativo

<p align="center">
  Visão resumida das principais telas do MAY Task Manager.
</p>

| **1. Processos** | **2. Desempenho** | **3. Histórico Diário** |
|:---:|:---:|:---:|
| <a href="docs/screenshots/01_processos.png"><img src="docs/screenshots/01_processos.png" width="240" alt="Processos" /></a> | <a href="docs/screenshots/02_desempenho.png"><img src="docs/screenshots/02_desempenho.png" width="240" alt="Desempenho" /></a> | <a href="docs/screenshots/03_historico.png"><img src="docs/screenshots/03_historico.png" width="240" alt="Histórico Diário" /></a> |
| Gestão cirúrgica de apps em 1º/2º plano e sistema com CPU e RAM | Frequências por núcleo, governadores e gráficos de CPU/RAM | Linha do tempo, consumo 24h, sono leve/profundo e aberturas |

| **4. Segundo Plano** | **5. Rede & Notificação** | **6. Ciclos de Bateria** |
|:---:|:---:|:---:|
| <a href="docs/screenshots/04_segundo_plano.png"><img src="docs/screenshots/04_segundo_plano.png" width="240" alt="Segundo Plano" /></a> | <a href="docs/screenshots/05_rede_config.png"><img src="docs/screenshots/05_rede_config.png" width="240" alt="Rede" /></a> | <a href="docs/screenshots/06_ciclos_bateria.png"><img src="docs/screenshots/06_ciclos_bateria.png" width="240" alt="Ciclos de Bateria" /></a> |
| Detector de anomalias, consumo em repouso e quebras de sono | Velocidade na barra de status, limites mínimos e amostragem | Curva de velocidade de recarga (Watts), temperatura e histórico |

| **7. Ferramentas** | **8. Configurações** | **9. Diagnóstico de Bateria** |
|:---:|:---:|:---:|
| <a href="docs/screenshots/07_ferramentas.png"><img src="docs/screenshots/07_ferramentas.png" width="240" alt="Ferramentas" /></a> | <a href="docs/screenshots/08_configuracoes.png"><img src="docs/screenshots/08_configuracoes.png" width="240" alt="Configurações" /></a> | <a href="docs/screenshots/09_bateria_visao_geral.png"><img src="docs/screenshots/09_bateria_visao_geral.png" width="240" alt="Bateria" /></a> |
| Gravação de logs com atalho QS, depuração e instalador root | Seleção de temas, atualizador embutido e canal beta | Tempo de tela ligada/apagada, Enforce Doze e qualidade de sinal |

| **10. Tráfego por App** | **11. Estatísticas Root** | **12. Zonas Térmicas** |
|:---:|:---:|:---:|
| <a href="docs/screenshots/10_rede_consumo_apps.png"><img src="docs/screenshots/10_rede_consumo_apps.png" width="240" alt="Tráfego por App" /></a> | <a href="docs/screenshots/11_bateria_root_status.png"><img src="docs/screenshots/11_bateria_root_status.png" width="240" alt="Estatísticas Root" /></a> | <a href="docs/screenshots/12_frequencias_termicas.png"><img src="docs/screenshots/12_frequencias_termicas.png" width="240" alt="Zonas Térmicas" /></a> |
| Ranking dos aplicativos que mais consumiram internet no dia | Capacidade nominal vs real, contagem de ciclos e tensão mV | Distribuição de tempo por estado da CPU e sensores térmicos |

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

### 5. Monitor de Rede (Network)
- Estatísticas detalhadas de consumo de dados por interface (Wi-Fi e Dados Móveis).
  - Identificação de pacotes ativos em segundo plano gerando tráfego.

### 6. Diagnostico de Bateria e Doze (Battery)
- Análise de consumo detalhada via `dumpsys batterystats`.
- Painel Enforce Doze: força a suspensão profunda imediatamente após o bloqueio de tela.

### 7. Detector de Anomalias (Background Watcher)
- Monitor de repouso configurável para alertar o usuário quando algum app exceder o limiar de consumo de processamento ou acordar o dispositivo excessivamente.
- Histórico de incidentes com registro de hora, aplicativo ofensor, severidade e métrica excedida.

---

## Recursos para Superusuario

O MAY Task Manager é funcional em dispositivos sem root (utilizando APIs padrão do Android, TrafficStats, UsageStats e NetworkStatsManager). Contudo, a presença de Superuser habilita um conjunto avançado de operações de baixo nível.

---

## Temas

<p align="center">
  <a href="docs/screenshots/themes_dynamic_oled_light.png">
    <img src="docs/screenshots/themes_dynamic_oled_light.png" width="100%" alt="Temas Dinâmico, Preto OLED e Claro" />
  </a>
</p>

A tela de configurações do MAY conta com seletor de temas com:

- **Liquid Glass:** Efeito de vidro líquido translúcido.
- **Monet Dinamico:** Extração inteligente da paleta tonal do wallpaper do usuário através da biblioteca Material You.
- **OLED Escuro:** Fundo `#000000` absoluto com elementos de alto contraste para economia máxima em telas AMOLED.
- **Modern Dark:** Tons modernos de cinza escuro e superfícies suaves para uso noturno confortável.
- **Light Clean:** Tema claro com alto contraste.

---

## Sistema de Atualizacoes e Canal Beta

O MAY Task Manager inclui um mecanismo integrado de entrega contínua através do GitHub Releases:

- **Canal de Versoes Beta:** O usuário pode ativar a opção **Testador de Versões Beta** nas configurações para receber versões preliminares e experimentais antes do lançamento público geral.
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

O projeto é configurado com compilação automatizada via GitHub Actions com otimizações:

- **Compilador:** Kotlin 2.0+ com plugin Compose.
- **Minificacao:** R8 habilitado com ofuscação, remoção de classes e métodos inacessíveis, além de empacotamento compacto de pacotes.
- **Shrinking de Recursos:** Remoção automática de recursos não utilizados para gerar um APK de tamanho mínimo.

---

## Creditos

- **[BatStats](https://github.com/mlm-games/BatStats)**: Inspiracao para o modulo de diagnostico e metricas de bateria.

---

## Licenca

Este projeto é software livre e de código aberto, distribuído sob os termos da licença **GNU General Public License v3.0 (GPL-3.0)**. Consulte o arquivo [LICENSE](LICENSE) para obter o texto completo da licença.
