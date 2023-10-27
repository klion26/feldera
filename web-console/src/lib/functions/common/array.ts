/**
 * Group elements into two based on a binary predicate
 * @param arr
 * @param predicate
 * @returns [is true, is false]
 */
export const partition = <T>(arr: T[], predicate: (v: T, i: number, ar: T[]) => boolean) =>
  arr.reduce(
    (acc, item, index, array) => {
      acc[+!predicate(item, index, array)].push(item)
      return acc
    },
    [[], []] as [T[], T[]]
  )

export function inUnion<T extends readonly string[]>(union: T, val: string): val is T[number] {
  return union.includes(val)
}

export function invariantUnion<T extends readonly string[]>(union: T, val: string): asserts val is T[number] {
  if (!union.includes(val)) {
    throw new Error(val + ' is not part of the union ' + union.toString())
  }
}

export function assertUnion<T extends readonly string[]>(union: T, val: string): T[number] {
  if (!union.includes(val)) {
    throw new Error(val + ' is not part of the union ' + union.toString())
  }
  return val
}

/**
 * Mutates the array
 * @param array
 * @param fromIndex
 * @param toIndex
 * @returns
 */
export function reorderElement<T>(array: T[], fromIndex: number, toIndex: number) {
  if (fromIndex === toIndex || fromIndex < 0 || toIndex < 0 || fromIndex >= array.length || toIndex >= array.length) {
    // No need to move if the indices are the same or out of bounds.
    return array
  }

  const [movedElement] = array.splice(fromIndex, 1)

  array.splice(toIndex, 0, movedElement)

  return array
}

/**
 * Replaces the first element in the array for which the replacement result isn't null
 * Mutates the array
 * @param array
 * @param replacement
 * @returns
 */
export function replaceElementInplace<T>(array: T[], replacement: (t: T) => T | null) {
  let value = null as T | null
  for (const [i, e] of array.entries()) {
    value = replacement(e)
    if (value !== null) {
      array[i] = value
      break
    }
  }
  return array
}

/**
 * Replaces the first element in the array for which the replacement result isn't null
 * Returns a new array
 * @param array
 * @param replacement
 * @returns
 */
export function replaceElement<T>(array: T[], replacement: (t: T) => T | null) {
  let value = null as T | null
  for (const [i, e] of array.entries()) {
    value = replacement(e)
    if (value !== null) {
      return array.slice(0, i).concat([value], array.slice(i + 1))
    }
  }
  return array
}
