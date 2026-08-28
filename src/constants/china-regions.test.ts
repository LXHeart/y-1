import { describe, expect, it } from 'vitest'
import {
  CHINA_REGIONS,
  CHINA_REGION_TREE,
  findCity,
  findProvince,
  getCitiesByProvince,
  getDistrictsByCity,
  getProvinces,
} from './china-regions'

describe('china-regions', () => {
  it('覆盖全部省级行政区并使用中文名称作为选项值', () => {
    expect(CHINA_REGIONS).toHaveLength(34)
    expect(getProvinces()).toBe(CHINA_REGIONS)
    expect(CHINA_REGIONS).toContainEqual({ value: '北京市', label: '北京市' })
    expect(CHINA_REGIONS).toContainEqual({ value: '广东省', label: '广东省' })
    expect(CHINA_REGIONS).toContainEqual({ value: '香港特别行政区', label: '香港特别行政区' })
  })

  it('按省名称或 code 获取城市并对行政区后缀宽容匹配', () => {
    expect(getCitiesByProvince('广东').map(({ value }) => value)).toContain('深圳市')
    expect(getCitiesByProvince('330000').map(({ value }) => value)).toContain('杭州市')
    expect(getCitiesByProvince('不存在的省')).toEqual([])
  })

  it('按省市联动获取区县', () => {
    expect(getDistrictsByCity('广东省', '深圳市').map(({ value }) => value)).toContain('南山区')
    expect(getDistrictsByCity('浙江', '杭州').map(({ value }) => value)).toContain('西湖区')
    expect(getDistrictsByCity('北京市', '北京市').map(({ value }) => value)).toContain('海淀区')
    expect(getDistrictsByCity('上海市', '上海市').map(({ value }) => value)).toContain('静安区')
    expect(getDistrictsByCity('广东省', '不存在的城市')).toEqual([])
  })

  it('补全石家庄县级市及其他可明确核对的遗漏', () => {
    const shijiazhuang = getDistrictsByCity('河北省', '石家庄市').map(({ value }) => value)
    expect(shijiazhuang).toHaveLength(21)
    expect(shijiazhuang).toEqual(expect.arrayContaining(['晋州市', '新乐市']))

    const handan = getDistrictsByCity('河北省', '邯郸市').map(({ value }) => value)
    expect(handan).toHaveLength(18)
    expect(handan).toEqual(expect.arrayContaining([
      '邱县', '鸡泽县', '广平县', '馆陶县', '魏县', '曲周县',
    ]))

    expect(getDistrictsByCity('广东省', '汕头市').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['南澳县']))
    expect(getDistrictsByCity('吉林省', '长春市').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['公主岭市']))
    expect(getDistrictsByCity('吉林省', '四平市').map(({ value }) => value))
      .not.toContain('公主岭市')
  })

  it('覆盖台湾六个直辖市及完整的市辖区数量', () => {
    const municipalityDistrictCounts = {
      '台北市': 12,
      '新北市': 29,
      '桃园市': 13,
      '台中市': 29,
      '台南市': 37,
      '高雄市': 38,
    } as const

    expect(getCitiesByProvince('台湾省').map(({ value }) => value))
      .toEqual(expect.arrayContaining(Object.keys(municipalityDistrictCounts)))

    for (const [municipality, districtCount] of Object.entries(municipalityDistrictCounts)) {
      expect(getDistrictsByCity('台湾省', municipality), municipality).toHaveLength(districtCount)
    }

    expect(getDistrictsByCity('台湾省', '高雄市').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['凤山区', '美浓区', '那玛夏区']))
    expect(getDistrictsByCity('台湾省', '台中市').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['太平区', '和平区', '大安区']))
    expect(getDistrictsByCity('台湾省', '台南市').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['永康区', '新营区', '安定区']))
    expect(getDistrictsByCity('台湾省', '桃园市').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['桃园区', '中坜区', '复兴区']))
  })

  it('覆盖台湾其余 16 个县市及完整乡镇市区', () => {
    const countyAndCityDistrictCounts = {
      '基隆市': 7,
      '新竹市': 3,
      '嘉义市': 2,
      '新竹县': 13,
      '苗栗县': 18,
      '彰化县': 26,
      '南投县': 13,
      '云林县': 20,
      '嘉义县': 18,
      '屏东县': 33,
      '宜兰县': 12,
      '花莲县': 13,
      '台东县': 16,
      '澎湖县': 6,
      '金门县': 6,
      '连江县': 4,
    } as const

    expect(getCitiesByProvince('台湾省')).toHaveLength(22)
    expect(getCitiesByProvince('台湾省').map(({ value }) => value))
      .toEqual(expect.arrayContaining(Object.keys(countyAndCityDistrictCounts)))

    for (const [countyOrCity, districtCount] of Object.entries(countyAndCityDistrictCounts)) {
      expect(getDistrictsByCity('台湾省', countyOrCity), countyOrCity).toHaveLength(districtCount)
    }

    expect(getDistrictsByCity('台湾', '宜兰').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['宜兰市', '罗东镇', '南澳乡']))
    expect(getDistrictsByCity('台湾省', '屏东县').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['屏东市', '琉球乡', '牡丹乡']))
    expect(getDistrictsByCity('台湾省', '金门县').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['金城镇', '烈屿乡', '乌坵乡']))

    const taiwan = findProvince('台湾省')
    const terminalRegionCount = taiwan?.children.reduce(
      (total, { children }) => total + children.length,
      0,
    )
    expect(terminalRegionCount).toBe(368)
  })

  it('补全不设区城市的官方镇街', () => {
    const dongguan = getDistrictsByCity('广东省', '东莞市').map(({ value }) => value)
    expect(dongguan).toHaveLength(32)
    expect(dongguan).toEqual(expect.arrayContaining([
      '莞城街道', '石碣镇', '樟木头镇', '高埗镇',
    ]))
    expect(dongguan).not.toContain('松山湖')

    const zhongshan = getDistrictsByCity('广东省', '中山市').map(({ value }) => value)
    expect(zhongshan).toHaveLength(23)
    expect(zhongshan).toEqual(expect.arrayContaining([
      '火炬开发区街道', '民众街道', '南朗街道', '神湾镇',
    ]))

    const danzhou = getDistrictsByCity('海南省', '儋州市').map(({ value }) => value)
    expect(danzhou).toHaveLength(16)
    expect(danzhou).toEqual(expect.arrayContaining([
      '那大镇', '峨蔓镇', '王五镇', '新州镇',
    ]))
    expect(danzhou).not.toContain('洋浦经济开发区')

    const jiayuguan = getDistrictsByCity('甘肃省', '嘉峪关市').map(({ value }) => value)
    expect(jiayuguan).toEqual(['新城镇', '峪泉镇', '文殊镇'])

    const shijie = findCity('广东省', '东莞市')?.children
      .find(({ name }) => name === '石碣镇')
    expect(shijie).toMatchObject({ key: 'district:441900:石碣镇' })
    expect(shijie).not.toHaveProperty('code')
  })

  it('覆盖省直辖县级行政区划的完整基线', () => {
    expect(getDistrictsByCity('河南省', '省直辖县级行政区划')).toHaveLength(1)
    expect(getDistrictsByCity('湖北省', '省直辖县级行政区划')).toHaveLength(4)
    expect(getDistrictsByCity('海南省', '省直辖县级行政区划')).toHaveLength(15)

    const xinjiangDirectAdmin = getDistrictsByCity(
      '新疆维吾尔自治区',
      '自治区直辖县级行政区划',
    ).map(({ value }) => value)
    expect(xinjiangDirectAdmin).toHaveLength(12)
    expect(xinjiangDirectAdmin).toEqual(expect.arrayContaining(['石河子市', '新星市', '白杨市']))
  })

  it('清理改制后不应与现行名称并存的旧选项', () => {
    expect(getDistrictsByCity('福建省', '三明市').map(({ value }) => value))
      .not.toContain('梅列区')
    expect(getDistrictsByCity('江西省', '南昌市').map(({ value }) => value))
      .not.toContain('湾里区')
    expect(getDistrictsByCity('山东省', '烟台市').map(({ value }) => value))
      .not.toContain('长岛县')
    expect(getDistrictsByCity('河南省', '洛阳市').map(({ value }) => value))
      .not.toContain('吉利区')
    expect(getDistrictsByCity('河南省', '周口市').map(({ value }) => value))
      .not.toContain('淮阳县')
    expect(getDistrictsByCity('湖北省', '黄石市').map(({ value }) => value))
      .toEqual(expect.arrayContaining(['铁山区']))
  })

  it('锁定全树城市与末级选项完整性基线', () => {
    const cities = CHINA_REGION_TREE.flatMap(({ children }) => children)
    const terminalRegions = cities.flatMap(({ children }) => children)

    expect(cities).toHaveLength(365)
    expect(terminalRegions).toHaveLength(3308)
    expect(cities.every(({ children }) => children.length > 0)).toBe(true)
  })

  it('保留省市查询码并将区县明确建模为内部稳定 key', () => {
    expect(CHINA_REGION_TREE).toHaveLength(34)
    expect(findProvince('440000')?.name).toBe('广东省')
    expect(findCity('广东省', '440300')?.name).toBe('深圳市')

    const haidian = findCity('北京市', '北京市')?.children
      .find(({ name }) => name === '海淀区')
    expect(haidian).toMatchObject({ key: 'district:110100:海淀区', name: '海淀区' })
    expect(haidian).not.toHaveProperty('code')

    const districtKeys = CHINA_REGION_TREE.flatMap(({ children: cities }) =>
      cities.flatMap(({ children: districts }) => districts.map(({ key }) => key)))
    expect(new Set(districtKeys).size).toBe(districtKeys.length)
    expect(Object.isFrozen(CHINA_REGION_TREE)).toBe(true)
    expect(Object.isFrozen(CHINA_REGION_TREE[0].children)).toBe(true)
  })
})
