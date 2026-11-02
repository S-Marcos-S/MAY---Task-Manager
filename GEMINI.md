# Diretrizes e Regras do Projeto MAY - Task Manager

## Regra Obrigatória: Design Minimalista, Compacto e Responsivo
- **Tudo no aplicativo DEVE ser feito de forma estritamente minimalista e compacta.**
- **Sem textos desnecessários** ou prolixos, e **sem botões com texto grande**.
- **Adaptação Responsiva:** Todos os cards, painéis e elementos devem caber e se adaptar perfeitamente ao tamanho da tela do dispositivo do usuário.
- **Sem Quebra de Linha em Botões:** É terminantemente proibido botões com nomes quebrados ou truncados por não caberem na linha. Os títulos e ações devem ser concisos, objetivos e calculados para manter integridade visual em qualquer largura de tela.
- **Proibição Absoluta de Textos Hardcoded:** É terminantemente proibido colocar qualquer texto de interface hardcoded no código. Todas as strings visíveis ao usuário DEVEM OBRIGATORIAMENTE ser extraídas para `res/values/strings.xml`, `res/values-pt/strings.xml` e `res/values-pt-rBR/strings.xml`.

## Regra Obrigatória: Validação Prévia em Branch de Testes (Proteção da Branch Main)
Para evitar poluir a branch `main` com commits que possam quebrar a compilação ou falhar na geração de APKs:
- **Toda alteração de código ou que envolva nova build deve ser testada primeiro na branch `test`:** É estritamente proibido enviar commits de novas implementações ou correções diretamente para a `main` sem validação prévia.
- **Commits triviais ou de documentação pura:** Mudanças que afetam apenas documentação (`README.md`, `docs/`, etc.) e que não alteram código funcional nem disparam nova build podem ser commitadas normalmente.
- **Fluxo de Trabalho Obrigatório para Builds:**
  1. Desenvolva as mudanças e utilize a branch de testes (`git checkout -b test` ou `git switch test`).
  2. Faça o bump de versão em `app/build.gradle.kts` e atualize `CHANGELOG_LATEST.md`.
  3. Realize o commit contendo a versão na mensagem e faça o push para a branch `test` (`git push origin test`).
  4. O GitHub Actions executará automaticamente o workflow de build (compilação e testes unitários) para a branch `test`.
  5. Acompanhe a execução da build até a conclusão (`gh run watch`). Caso ocorra qualquer falha, investigue e corrija na própria branch `test`.
  6. **SOMENTE APÓS A BUILD PASSAR COM SUCESSO (`completed` / `success`) na branch `test`:** faça o merge para a branch `main` e envie o push para gerar a Release e APK oficial:
     ```bash
     git checkout main
     git merge test --ff-only
     git push origin main
     ```

## Regra Obrigatória: Bump de Versão/Release Apenas para Mudanças Relevantes (Proibido para Mudanças Bestas/Triviais)
- **Mudanças Bestas / Triviais (NÃO gerar build):**
  - Para alterações pequenas, triviais ou que não alteram o funcionamento do aplicativo em si:
    - **NUNCA** faça bump de versão (`versionCode` e `versionName` em `app/build.gradle.kts`).
    - **NUNCA** altere o `CHANGELOG_LATEST.md`.
    - **NUNCA** inclua número de versão na mensagem do commit.
    - Faça apenas um **commit normal**.
- **Mudanças Relevantes no Aplicativo (GERAR build):**
  - Sempre que for realizado um commit que traga uma **nova funcionalidade/feature**, **correção de bug real**, **refatoração funcional** ou **mudança visual direta no aplicativo**:
    - **DEVE-SE** incrementar o número de release/versão (`versionCode` e `versionName` no arquivo `app/build.gradle.kts`).
    - **DEVE-SE** atualizar o arquivo `CHANGELOG_LATEST.md` (resumido e compacto).
    - **OBRIGATORIAMENTE** incluir o número da versão na mensagem/comentário do commit (exemplo: `1.0.0` ou `v1.0.0`).
    - Seguir rigorosamente o fluxo de validação prévia na branch `test` antes da `main`.

## Regra Obrigatória: Changelogs Resumidas e Compactas Pré-Build
Sempre que forem realizadas novas implementações, correções ou modificações no código, o arquivo `CHANGELOG_LATEST.md` na raiz do projeto **DEVE** ser atualizado antes de efetuar o commit e disparar a build do GitHub Actions.

- **Formato Estrito:** As changelogs **DEVEM SER RESUMIDAS E COMPACTAS**. É estritamente proibido incluir textos longos, parágrafos explicativos prolixos ou detalhes técnicos excessivos. Foque apenas no que mudou de forma direta, clara e concisa (tópicos curtos).

## Regra Obrigatória: Proibição Estrita de Builds Locais e Gradle
NUNCA faça build local nem execute comandos `./gradlew` (tais como `./gradlew assembleDebug`, `./gradlew compileDebugKotlin`, `./gradlew build`, etc.) no ambiente local/Termux. A compilação e geração dos APKs é SEMPRE e EXCLUSIVAMENTE realizada pelo GitHub Actions.

## Regra Obrigatória: Verificação Rigorosa e Estrita de Importações (Zero Tolerância para Imports Ausentes)
Sempre que qualquer arquivo de código for criado ou editado e utilizar qualquer classe, objeto, função, extensão ou recurso externo, **DEVE-SE OBRIGATORIAMENTE conferir a lista de imports do topo do arquivo** antes de salvar e commitar.
- **Tolerância Zero:** Erros por falta de importação são inaceitáveis. Toda referência externa introduzida precisa ter seu respectivo `import` explicitamente verificado e confirmado no topo do arquivo.

## Regra Obrigatória: Acompanhamento de Builds do GitHub Actions
Sempre que for realizado um commit/push que inicie uma build no GitHub Actions, a execução do workflow **DEVE OBRIGATORIAMENTE ser acompanhada até o final** (via API do GitHub ou logs de workflow runs) para verificar se a compilação, os testes unitários e a geração da Release/APK foram concluídos com sucesso (`completed` / `success`).
