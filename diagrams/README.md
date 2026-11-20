# Diagrams

| File | Diagram (Chapter 3) |
|---|---|
| `use-case.puml` | Use-case diagram (PlantUML) |
| `system-architecture.mmd` | System architecture |
| `data-flow.mmd` | Data-flow diagram, level 1 |
| `erd.mmd` | Entity relationship diagram |
| `class-diagram.mmd` | Class diagram (core classes) |
| `sequence-monitoring-tick.mmd` | Sequence diagram: one sampling tick |
| `activity-monitoring.mmd` | Activity diagram: monitoring session |
| `ml-pipeline.mmd` | Machine-learning pipeline |

## Exporting to PNG/SVG for the dissertation

**Easiest (no install):** paste a `.mmd` file into https://mermaid.live and download PNG or SVG. Paste the `.puml` file into https://www.plantuml.com/plantuml.

**Command line (Windows, with Node.js):**

```powershell
npm install -g @mermaid-js/mermaid-cli
mmdc -i diagrams\erd.mmd -o diagrams\erd.png -w 1600 -b white
```

**PlantUML (needs Java):** download `plantuml.jar`, then run `java -jar plantuml.jar diagrams\use-case.puml`.

VS Code users can install the "Markdown Preview Mermaid Support" and "PlantUML" extensions to preview diagrams while editing.
