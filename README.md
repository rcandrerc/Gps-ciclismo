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
- **Gravação em segundo plano** (serviço em primeiro plano): funciona com a tela apagada.
- **Histórico** com mapa do percurso (OpenStreetMap, sem chave de API) e resumo do treino.
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

## Próximos passos

- Perguntar por voz / botão do fone para ouvir o resumo na hora
- Exportar GPX/FIT (Strava, TrainingPeaks)
- Gráficos de potência/FC e tempo em cada zona
- Auto-pausa
- Login e sincronização para compartilhar com o treinador
