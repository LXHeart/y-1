<script setup lang="ts">
import { computed } from 'vue'

interface StyleOption {
  code: string
  name: string
  description: string
}

interface Props {
  variant: 'formula' | 'full'
  testScope: 'question' | 'titles' | 'content'
  radioNameBase: string
  titleFormula?: string
  genre?: string
  style?: string
  formulaOptions: StyleOption[]
  genreOptions: StyleOption[]
  styleOptions: StyleOption[]
  loading: boolean
  error: string
}

const props = withDefaults(defineProps<Props>(), {
  titleFormula: undefined,
  genre: undefined,
  style: undefined,
})

const emit = defineEmits<{
  'update:titleFormula': [value: string]
  'update:genre': [value: string]
  'update:style': [value: string]
  retry: []
}>()

const selectedFormula = computed(() =>
  props.formulaOptions.find(item => item.code === props.titleFormula)
)

const selectedGenre = computed(() =>
  props.genreOptions.find(item => item.code === props.genre)
)

const selectedStyle = computed(() =>
  props.styleOptions.find(item => item.code === props.style)
)

const loadingCheckField = computed(() => {
  if (props.variant === 'formula') {
    return !props.formulaOptions.length
  }
  return !props.genreOptions.length
})

const retryTestId = computed(() => {
  return props.testScope === 'content' ? 'style-skills-retry-content' : 'style-skills-retry'
})

const titleFormulaModel = computed({
  get: () => props.titleFormula,
  set: (value: string) => emit('update:titleFormula', value)
})

const genreModel = computed({
  get: () => props.genre,
  set: (value: string) => emit('update:genre', value)
})

const styleModel = computed({
  get: () => props.style,
  set: (value: string) => emit('update:style', value)
})
</script>

<template>
  <div class="style-skills" :data-test="`style-skills-${testScope}`">
    <p v-if="loading && loadingCheckField" class="field-note">
      {{ variant === 'formula' ? '正在加载标题套路…' : '正在加载体裁与文风…' }}
    </p>
    <template v-else>
      <fieldset v-if="variant === 'formula'" class="form-field style-field">
        <legend>标题套路 <span class="required-mark" aria-hidden="true">*</span></legend>
        <div class="option-grid" role="radiogroup" aria-label="标题套路">
          <label
            v-for="item in formulaOptions"
            :key="item.code"
            class="style-option"
            :class="{ active: titleFormula === item.code }"
          >
            <input
              v-model="titleFormulaModel"
              type="radio"
              :name="radioNameBase"
              :value="item.code"
              :data-test="`skill-formula-${item.code}`"
            >
            {{ item.name }}
          </label>
        </div>
        <p v-if="selectedFormula" class="field-note" data-test="skill-formula-desc">{{ selectedFormula.description }}</p>
      </fieldset>
      <fieldset v-if="variant === 'full'" class="form-field style-field">
        <legend>内容体裁 <span class="required-mark" aria-hidden="true">*</span></legend>
        <div class="option-grid" role="radiogroup" aria-label="内容体裁">
          <label
            v-for="item in genreOptions"
            :key="item.code"
            class="style-option"
            :class="{ active: genre === item.code }"
          >
            <input v-model="genreModel" type="radio" name="content-genre" :value="item.code" :data-test="`skill-genre-${item.code}`">
            {{ item.name }}
          </label>
        </div>
        <p v-if="selectedGenre" class="field-note" data-test="skill-genre-desc">{{ selectedGenre.description }}</p>
      </fieldset>
      <fieldset v-if="variant === 'full'" class="form-field style-field">
        <legend>文风口吻 <span class="required-mark" aria-hidden="true">*</span></legend>
        <div class="option-grid" role="radiogroup" aria-label="文风口吻">
          <label
            v-for="item in styleOptions"
            :key="item.code"
            class="style-option"
            :class="{ active: style === item.code }"
          >
            <input v-model="styleModel" type="radio" name="content-style" :value="item.code" :data-test="`skill-style-${item.code}`">
            {{ item.name }}
          </label>
        </div>
        <p v-if="selectedStyle" class="field-note" data-test="skill-style-desc">{{ selectedStyle.description }}</p>
      </fieldset>
    </template>
    <div v-if="error" class="style-catalog-error">
      <p class="field-note" role="alert">{{ error }}</p>
      <button type="button" class="btn-secondary btn-sm" :data-test="retryTestId" @click="emit('retry')">重试</button>
    </div>
  </div>
</template>

<style scoped>
.style-skills {
  display: grid;
  gap: 12px;
}

.style-field {
  margin: 0;
  padding: 0;
  border: none;
  display: grid;
  gap: 8px;
}

.style-field legend {
  padding: 0;
  font-size: 0.84rem;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.required-mark {
  color: var(--color-danger);
}

.style-catalog-error {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.style-catalog-error .field-note[role='alert'] {
  color: var(--color-danger);
}

/* 检查步/风格区共享的父级 scoped 类复制（scoped 不穿透子组件，样式须随迁） */
.field-note {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 0.85rem;
  line-height: 1.6;
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  border-radius: var(--radius-md);
  cursor: pointer;
  font-size: 0.84rem;
  font-weight: 600;
  transition: transform var(--duration-fast) var(--ease-out), background var(--duration-fast) var(--ease-out), border-color var(--duration-fast) var(--ease-out), opacity var(--duration-fast) var(--ease-out);
  min-height: 38px;
  padding: 0 var(--space-md);
  border-radius: var(--radius-sm);
}

.btn-sm {
  min-height: 30px;
  padding: 0 var(--space-sm);
}

@media (max-width: 720px) {
  .btn-secondary,
  .btn-sm {
    width: 100%;
  }
}
</style>
