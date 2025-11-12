package protocolos;

import java.io.Serializable;

public class ErrorProtocolo implements Serializable {
    private static final long serialVersionUID = 1L;
    public int codigo;
    public String detalle;

    public ErrorProtocolo(int codigo, String detalle) {
        this.codigo = codigo;
        this.detalle = detalle;
    }
}
