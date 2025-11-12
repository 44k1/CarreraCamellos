package protocolos;

import java.io.Serializable;

public class EventoCarrera implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum TipoEvento { SALIDA, PASO, CAIDA, META }

    public TipoEvento tipo;
    public String idCliente;
    public long tMarca;
    public int pos;

    public EventoCarrera(TipoEvento tipo, String idCliente, long tMarca, int pos) {
        this.tipo = tipo;
        this.idCliente = idCliente;
        this.tMarca = tMarca;
        this.pos = pos;
    }
}
