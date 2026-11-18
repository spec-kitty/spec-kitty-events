# `area/` promises

A `manual` row never counts towards `JVM binding`: the one gating row is JVM-bound, so it is `full`.

JVM binding: full

| Promise | Guarantee | Requirement | Binding |
|---------|-----------|-------------|---------|
| `PR-EVENTS-AREA-001` | It holds. | fixture | python, jvm |
| `PR-EVENTS-AREA-002` | It holds where CI cannot reach. | fixture | manual |
