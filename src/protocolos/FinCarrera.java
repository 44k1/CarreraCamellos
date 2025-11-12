package protocolos;

import java.io.Serializable;
import java.util.List;

public class FinCarrera implements Serializable {
    private static final long serialVersionUID = 1L;
    public int idGrupo;
    public List<String> ranking;

    public FinCarrera(int idGrupo, List<String> ranking) {
        this.idGrupo = idGrupo;
        this.ranking = ranking;
    }
}
