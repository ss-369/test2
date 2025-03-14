package com.sismics.reader.core.model.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Id;
import javax.persistence.Table;

import com.google.common.base.Objects;
import com.sismics.reader.core.constant.ConfigType;

/**
 * Configuration parameter entity.
 * 
 * @author jtremeaux
 */
@Entity
@Table(name = "T_CONFIG")
public class Config {
    /**
     * Configuration parameter ID.
     */
    @Id
    @Column(name = "CFG_ID_C", length = 50)
    @Enumerated(EnumType.STRING)
    private ConfigType id;
    
    /**
     * Configuration parameter value.
     */
    @Column(name = "CFG_VALUE_C", length = 250)
    private String value;

    /**
     * Getter of id.
     *
     * @return id
     */
    public ConfigType getId() {
        return id;
    }

    /**
     * Setter of id.
     *
     * @param id id
     */
    public void setId(ConfigType id) {
        this.id = id;
    }

    /**
     * Getter of value.
     *
     * @return value
     */
    public String getValue() {
        return value;
    }

    /**
     * Setter of value.
     *
     * @param value value
     */
    public void setValue(String value) {
        this.value = value;
    }
    
    @Override
    public String toString() {
        return Objects.toStringHelper(this)
                .add("id", id)
                .toString();
    }
}
