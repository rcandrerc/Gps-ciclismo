# Plano de treinos (calendário do app)

O app baixa `calendario.json` deste diretório (branch `main`) quando a tela
**Calendário de treinos** abre, e guarda uma cópia para usar sem internet.
Para mudar o plano basta editar este arquivo e juntar na `main`; não precisa de versão nova do app.

```json
{
  "name": "Volta aos treinos (FTP)",
  "updated": "2026-11-14",
  "days": [
    {
      "date": "2026-11-14",
      "title": "Retorno Z2",
      "where": "rolo",
      "notes": "Texto livre que aparece no dia.",
      "workout": { "name": "...", "blocks": [ {"durationSec": 300, "target": {"type": "zone", "zone": 2}} ] }
    },
    { "date": "2026-11-15", "title": "Descanso" }
  ]
}
```

- `date` (obrigatório): AAAA-MM-DD.
- `workout`: mesmo formato dos treinos do app (`WorkoutPlan`); alvo `{"type":"watts","watts":240}`, `{"type":"zone","zone":2}` ou `{"type":"free"}`. Sem `workout`, o dia é descanso.
- `title`, `where` (`rolo` ou `rua`) e `notes` são opcionais.
- Dia que não aparece na lista fica vazio no calendário.
