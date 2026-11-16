# GPS Ciclismo

App Android (Kotlin + Jetpack Compose) para treinos de ciclismo por potência.

## O que faz (v0.1)

- **Sensores Bluetooth LE** com os perfis padrão: frequência cardíaca (0x180D), velocidade/cadência (0x1816) e potência (0x1818). O medidor de potência também pode ser usado como fonte de cadência. Reconecta sozinho se o sensor cair.
- **GPS** a cada 1 s: distância, velocidade atual, média (em movimento) e máxima.
- **Treino por zonas** (Coggan): blocos com tempo e alvo em watts ou em zona (Z1–Z7), calculados a partir do FTP.
- **Treinador por voz** em português:
  - anuncia cada bloco e avisa 10 s antes da troca;
  - usa a média de 3 s da potência e, se você ficar fora da faixa por 5 s, fala "Tá abaixo, sobe pra 240 watts" ou "Tá muito acima, baixa pra 130 watts" (com bipe agudo/grave), repetindo a cada 15 s;
  - resumo periódico (distância, tempo, média, potência, batimento);
  - abaixa o volume da música enquanto fala.
- **Perguntas por voz**: no fone, 1 toque = resumo completo (bloco, alvo, quanto falta, média do bloco, velocidade, FC, cadência, distância); 2 toques = fazer uma pergunta ("qual minha velocidade?", "quanto falta?", "me atualize"). Também há botões Resumo e Perguntar na tela.
- **Escolha da voz**: no Perfil, lista as vozes em português do Android com amostra e velocidade da fala.
- **Gravação em segundo plano** (serviço em primeiro plano): funciona com a tela apagada.
- **Histórico** com mapa do percurso (OpenStreetMap, sem chave de API) e resumo do treino.
- **Tela de treino personalizável**: segure qualquer campo para editar (remover, adicionar, mudar de posição e de tamanho). O layout fica salvo.
- **Atualização pelo app**: ao abrir, ele confere se há versão nova no GitHub e oferece baixar e instalar.
- **Perfil** local: nome, peso, altura e FTP (mostra W/kg e as zonas).

## Como instalar

Cada push na `main` gera o APK e publica na release **Última versão**. No celular, abra
https://github.com/rcandrerc/gps-ciclismo/releases/tag/ultima-versao, baixe o `gps-ciclismo.apk`
e abra (permita "instalar apps desconhecidos").

O APK é assinado com uma chave fixa de desenvolvimento (`app/debug.keystore`, não é segredo), então versões novas instalam por cima sem perder os treinos.

## Estrutura

```
app/src/main/java/br/com/gpsciclismo/
  core/        lógica pura (testada): parsers BLE, cadência, zonas, treinador, estatísticas
  ble/         conexão com os sensores
  recording/   gravação do treino (GPS + sensores + voz) e serviço em primeiro plano
  voice/       síntese de voz e bipes
  data/        banco Room (treinos, pontos, planos) e preferências
  ui/          telas em Compose
```

Testes: `./gradlew testDebugUnitTest`.

## Conta e nuvem (Firebase)

Os treinos e o perfil ficam no aparelho e, com conta, sincronizam pelo Firebase
(projeto `gps-ciclismo-92871`: login com Google + Firestore, via API REST, sem Google Play Services,
então funciona no tablet Amazon Fire).

- Login com Google: o app abre [`docs/login.html`](docs/login.html) (GitHub Pages) no navegador; a página entra
  pelo Firebase e devolve o app com `gpsciclismo://auth`. O app confere um código de uso único antes de aceitar.
- Estrutura: `users/{uid}` (perfil), `users/{uid}/rides/{uuid}` (resumo), `.../points/{n}` (pontos compactados).
- Regras do Firestore: [`firebase/firestore.rules`](firebase/firestore.rules) (cada pessoa só vê a própria pasta).
- A configuração de cliente do Firebase (chave de API web e ID do projeto) fica em `app/build.gradle.kts`.
  Ela não é segredo: vai dentro de todo APK; quem protege os dados são as regras do Firestore.

## Próximos passos

- Perguntar por voz / botão do fone para ouvir o resumo na hora
- Exportar GPX/FIT (Strava, TrainingPeaks)
- Gráficos de potência/FC e tempo em cada zona
- Auto-pausa
- Compartilhar treinos com o treinador
