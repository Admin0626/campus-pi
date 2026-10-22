import { computed, ref, watch } from "vue";

// Selection is deliberately scoped to the visible page, and reset on every reload.
export function useAdminSelection(rows, getId, eligible, loading) {
  const selected = ref([]);
  const available = computed(() => rows.value.filter(eligible).map(row => String(getId(row))));
  const allSelected = computed(() => available.value.length > 0 && available.value.every(id => selected.value.includes(id)));
  function toggle(row) {
    if (loading.value || !eligible(row)) return;
    const id = String(getId(row));
    selected.value = selected.value.includes(id) ? selected.value.filter(value => value !== id) : [...selected.value, id];
  }
  function toggleAll() {
    if (!loading.value) selected.value = allSelected.value ? [] : [...available.value];
  }
  watch([rows, loading], () => { selected.value = []; });
  return { selected, available, allSelected, toggle, toggleAll };
}
