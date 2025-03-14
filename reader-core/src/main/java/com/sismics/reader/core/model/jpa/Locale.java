package com.sismics.reader.core.model.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

import com.google.common.base.Objects;

/**
 * Locale entity.
 * 
 * @author jtremeaux
 */
@Entity
@Table(name = "T_LOCALE")
public class Locale {
    /**
     * Locale ID (ex: fr_FR).
     */
    @Id
    @Column(name = "LOC_ID_C", length = 10)
    private String id;

    /**
     * Getter of id.
     *
     * @return id
     */
    public String getId() {
        return id;
    }

    /**
     * Setter of id.
     *
     * @param id id
     */
    public void setId(String id) {
        this.id = id;
    }
    
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .toString();
    }
}
