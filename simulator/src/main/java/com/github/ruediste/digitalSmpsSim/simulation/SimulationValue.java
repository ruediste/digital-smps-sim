package com.github.ruediste.digitalSmpsSim.simulation;

public class SimulationValue<T> {
    private T value;
    private T nextValue;

    public SimulationValue(Circuit circuit, T initial) {
        circuit.values.add(this);
        this.value = initial;
        this.nextValue = initial;
    }

    public T get() {
        return value;
    }

    public void set(T nextValue) {
        this.nextValue = nextValue;
    }

    public void initialize(T value) {
        this.value = value;
        this.nextValue = value;
    }

    void transferNextValue() {
        this.value = nextValue;
    }
}
