package br.com.gpsciclismo.core

/** Campos que podem aparecer na tela de treino ao vivo. */
enum class LiveField(val label: String) {
    SPEED("Velocidade"),
    AVG_SPEED("Vel. média"),
    MAX_SPEED("Vel. máxima"),
    DISTANCE("Distância"),
    TIME("Tempo"),
    // O nome POWER_3S fica por compatibilidade com layouts salvos; a janela da média é configurável.
    POWER_3S("Potência (média)"),
    POWER_NOW("Potência instantânea"),
    AVG_POWER("Pot. média"),
    MAX_POWER("Pot. máxima"),
    W_PER_KG("W/kg"),
    POWER_ZONE("Zona de potência"),
    HEART_RATE("Batimento"),
    AVG_HR("FC média"),
    CADENCE("Cadência"),
    KCAL("Calorias"),
}

/** Tamanho do campo: pequeno (meia largura), largo (linha inteira) ou grande (linha inteira, número enorme). */
enum class TileSize { SMALL, WIDE, BIG;
    fun next(): TileSize = entries[(ordinal + 1) % entries.size]
}

data class Tile(val field: LiveField, val size: TileSize)

/** Layout da tela, salvo como texto "CAMPO:TAMANHO,CAMPO:TAMANHO". */
data class DashboardLayout(val tiles: List<Tile>) {

    fun encode(): String = tiles.joinToString(",") { "${it.field.name}:${it.size.name}" }

    fun remove(index: Int) = copy(tiles = tiles.filterIndexed { i, _ -> i != index })

    fun add(field: LiveField) = copy(tiles = tiles + Tile(field, TileSize.SMALL))

    fun resize(index: Int) = copy(tiles = tiles.mapIndexed { i, t -> if (i == index) t.copy(size = t.size.next()) else t })

    fun move(from: Int, to: Int): DashboardLayout {
        if (from !in tiles.indices || to !in tiles.indices || from == to) return this
        val list = tiles.toMutableList()
        val t = list.removeAt(from)
        list.add(to, t)
        return copy(tiles = list)
    }

    /** Campos que ainda não estão na tela. */
    fun available(): List<LiveField> = LiveField.entries.filter { f -> tiles.none { it.field == f } }

    /**
     * Agrupa em linhas: até [perRow] campos pequenos dividem uma linha (2 no celular, 3 ou 4 no tablet);
     * largo/grande ocupam a linha inteira.
     */
    fun rows(perRow: Int = 2): List<List<IndexedValue<Tile>>> {
        val n = perRow.coerceAtLeast(1)
        val rows = mutableListOf<List<IndexedValue<Tile>>>()
        val pending = mutableListOf<IndexedValue<Tile>>()
        fun flush() { if (pending.isNotEmpty()) { rows += pending.toList(); pending.clear() } }
        tiles.withIndex().forEach { t ->
            if (t.value.size == TileSize.SMALL) {
                pending += t
                if (pending.size == n) flush()
            } else {
                flush()
                rows += listOf(t)
            }
        }
        flush()
        return rows
    }

    companion object {
        val DEFAULT = DashboardLayout(listOf(
            Tile(LiveField.TIME, TileSize.SMALL),
            Tile(LiveField.DISTANCE, TileSize.SMALL),
            Tile(LiveField.SPEED, TileSize.BIG),
            Tile(LiveField.AVG_SPEED, TileSize.SMALL),
            Tile(LiveField.MAX_SPEED, TileSize.SMALL),
            Tile(LiveField.POWER_3S, TileSize.SMALL),
            Tile(LiveField.AVG_POWER, TileSize.SMALL),
            Tile(LiveField.HEART_RATE, TileSize.SMALL),
            Tile(LiveField.CADENCE, TileSize.SMALL),
        ))

        fun decode(s: String?): DashboardLayout {
            if (s.isNullOrBlank()) return DEFAULT
            val tiles = s.split(",").mapNotNull { part ->
                val (f, z) = part.split(":").takeIf { it.size == 2 } ?: return@mapNotNull null
                val field = LiveField.entries.find { it.name == f } ?: return@mapNotNull null
                val size = TileSize.entries.find { it.name == z } ?: TileSize.SMALL
                Tile(field, size)
            }
            return DashboardLayout(tiles)
        }
    }
}
