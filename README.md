[README.md](https://github.com/user-attachments/files/23632537/README.md)
# Carrera de Camellos 🐪

Juego multijugador en tiempo real desarrollado en Java donde los jugadores controlan camellos en una carrera sincronizada a través de una arquitectura cliente-servidor TCP.

## 🎮 Características

- **Multijugador sincronizado**: Hasta 6 jugadores simultáneos organizados en 3 grupos de 2
- **Cuenta atrás visual**: Sincronización de inicio con contador de 3 segundos en pantalla grande
- **Comunicación TCP persistente**: Streams bidireccionales para máxima fiabilidad
- **Redistribución de eventos**: El servidor sincroniza posiciones entre jugadores del mismo grupo en tiempo real
- **Podio visual**: Resultado final con medallas (🥇🥈🥉) y ranking completo
- **Advance 1 paso por clic**: Control simple y preciso del movimiento

## 🏗️ Arquitectura

```
Servidor (TCP:5000)
    ├── Fase 1: Emparejamiento (AsignacionGrupo)
    ├── Fase 2: Sincronización (CuentaAtras)
    ├── Fase 3: Carrera (EventoCarrera)
    └── Fase 4: Finalización (FinCarrera)

Cliente (conecta vía TCP)
    ├── Envía: SolicitudConexion, EventoCarrera, Heartbeat
    └── Recibe: AsignacionGrupo, CuentaAtras, InicioCarrera, EventoCarrera, FinCarrera
```

## 📋 Requisitos

- Java 21+
- `camel.png` en el directorio raíz del cliente (50x40 píxeles recomendado)

## 🚀 Ejecución

### Servidor
```bash
java servidor.ServidorEmparejamiento
```

La consola mostrará:
```
[SERVIDOR] ========================================
[SERVIDOR] Servidor iniciado en puerto 5000
[SERVIDOR] TAM_GRUPO = 2
[SERVIDOR] META = 650
[SERVIDOR] ========================================
[SERVIDOR] Esperando clientes...
```

### Cliente
```bash
java cliente.ClienteCamel
```

Se solicitará ingresar un ID de jugador (ej: "Jugador1").

## 🎯 Flujo de Juego

1. **Conexión**: Cliente se conecta al servidor en `localhost:5000`
2. **Emparejamiento**: Espera a que 2 jugadores estén listos
3. **Cuenta atrás**: Visualiza "3... 2... 1... ¡YA!" en pantalla grande
4. **Carrera**: Click en "AVANZAR CAMELLO" = +1 posición (20px)
5. **Meta**: Primer jugador en alcanzar 650px gana
6. **Podio**: Visualización del ranking final con medallas

## 📦 Estructura de Clases

### Protocolo (`protocolos/`)
- `SolicitudConexion`: Solicitud inicial del cliente
- `AsignacionGrupo`: Información del grupo asignado
- `CuentaAtras`: Countdown sincronizado (3, 2, 1, 0)
- `InicioCarrera`: Señal para habilitar controles
- `EventoCarrera`: Eventos de movimiento/meta
- `Heartbeat`: Pulso para mantener conexión viva
- `FinCarrera`: Ranking final con clasificación

### Servidor (`servidor/`)
- `ServidorEmparejamiento`: Gestiona conexiones, emparejamientos y sincronización

### Cliente (`cliente/`)
- `ClienteCamel`: Interfaz gráfica y lógica de juego

## 🔧 Configuración

En `ServidorEmparejamiento.java`:
```java
private final int puertoControl = 5000;    // Puerto TCP del servidor
private final int TAM_GRUPO = 2;            // Jugadores por grupo
private final int MAX_GRUPOS = 3;           // Máximo de grupos simultáneos
private final int META = 650;               // Píxeles para llegar a meta
private static final long TIMEOUT_HEARTBEAT = 20000;  // Timeout en ms
```

## 🌐 Conexión Remota

Para conectar desde otra máquina, modifica en `ClienteCamel.main()`:
```java
cliente.conectarServidor("192.168.x.x", 5000);  // IP del servidor
```

## 📊 Sincronización

- **Heartbeat**: Clientes envían pulso cada 3 segundos
- **Cuenta atrás**: 1 segundo entre cada número
- **Redistribución**: El servidor envía eventos a otros clientes <5ms
- **Finalización**: Todos reciben ranking idéntico simultáneamente

## 🐛 Logs

El sistema imprime logs detallados:
- `[SERVIDOR]`: Eventos del servidor
- `[CLIENTE]`: Eventos del cliente
- `[SERVIDOR PROXY]` / `[CLIENTE RX]`: Comunicación en tiempo real
- `[SERVIDOR MONITOR]`: Monitoreo de timeouts


