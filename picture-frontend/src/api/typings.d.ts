declare namespace API {
  type BaseResponseBoolean = {
    code?: number
    data?: boolean
    message?: string
  }

  type BaseResponseCaptchaVO = {
    code?: number
    data?: CaptchaVO
    message?: string
  }

  type BaseResponseLoginUserVO = {
    code?: number
    data?: LoginUserVO
    message?: string
  }

  type BaseResponseLong = {
    code?: number
    data?: string
    message?: string
  }

  type BaseResponsePagePicture = {
    code?: number
    data?: PagePicture
    message?: string
  }

  type BaseResponsePagePictureVO = {
    code?: number
    data?: PagePictureVO
    message?: string
  }

  type BaseResponsePageUserVO = {
    code?: number
    data?: PageUserVO
    message?: string
  }

  type BaseResponsePublicUserVO = {
    code?: number
    data?: PublicUserVO
    message?: string
  }

  type BaseResponsePicture = {
    code?: number
    data?: Picture
    message?: string
  }

  type BaseResponsePictureTagCategory = {
    code?: number
    data?: PictureTagCategory
    message?: string
  }

  type BaseResponsePictureVO = {
    code?: number
    data?: PictureVO
    message?: string
  }

  type BaseResponseString = {
    code?: number
    data?: string
    message?: string
  }

  type BaseResponseUser = {
    code?: number
    data?: User
    message?: string
  }

  type BaseResponseUserAddResult = {
    code?: number
    data?: UserAddResult
    message?: string
  }

  type BaseResponseUserVO = {
    code?: number
    data?: UserVO
    message?: string
  }

  type DeleteRequest = {
    id?: string
  }

  type CaptchaVO = {
    captchaId?: string
    image?: string
    expiresInSeconds?: number
  }

  type getPictureByIdParams = {
    id: string
  }

  type getPictureVOByIdParams = {
    id: string
  }

  type getUserByIdParams = {
    id: number
  }

  type getUserVOByIdParams = {
    id: number
  }

  type LoginUserVO = {
    id?: string
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    passwordChangeRequired?: boolean
    createTime?: string
    updateTime?: string
  }

  type OrderItem = {
    column?: string
    asc?: boolean
  }

  /**
   * 公开用户信息：公开接口不返回账号和角色
   */
  type PublicUserVO = {
    id?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
  }

  type PagePicture = {
    records?: Picture[]
    total?: number
    size?: number
    current?: number
    orders?: OrderItem[]
    optimizeCountSql?: PagePicture
    searchCount?: PagePicture
    optimizeJoinOfCountSql?: boolean
    maxLimit?: string
    countId?: string
    pages?: number
  }

  type PagePictureVO = {
    records?: PictureVO[]
    total?: number
    size?: number
    current?: number
    orders?: OrderItem[]
    optimizeCountSql?: PagePictureVO
    searchCount?: PagePictureVO
    optimizeJoinOfCountSql?: boolean
    maxLimit?: string
    countId?: string
    pages?: number
  }

  type PageUserVO = {
    records?: UserVO[]
    total?: number
    size?: number
    current?: number
    orders?: OrderItem[]
    optimizeCountSql?: PageUserVO
    searchCount?: PageUserVO
    optimizeJoinOfCountSql?: boolean
    maxLimit?: string
    countId?: string
    pages?: number
  }

  type Picture = {
    id?: string
    url?: string
    name?: string
    introduction?: string
    category?: string
    tags?: string
    picSize?: string
    downloadCount?: string
    picWidth?: number
    picHeight?: number
    picScale?: number
    picFormat?: string
    userId?: string
    createTime?: string
    editTime?: string
    updateTime?: string
    isDelete?: number
    deletedAt?: string
    deletedBy?: string
    reviewStatus?: number
    reviewMessage?: string
    reviewerId?: string
    reviewTime?: string
    machineReviewStatus?: number
    machineReviewLabel?: string
    machineReviewScore?: number
    machineReviewTime?: string
    textReviewStatus?: number
    textReviewLabel?: string
    textReviewScore?: number
    textReviewTime?: string
  }

  type PictureEditRequest = {
    id?: string
    name?: string
    introduction?: string
    category?: string
    tags?: string[]
  }

  type PictureQueryRequest = {
    current?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: string
    name?: string
    introduction?: string
    category?: string
    tags?: string[]
    picSize?: string
    picWidth?: number
    picHeight?: number
    picScale?: number
    picFormat?: string
    searchText?: string
    userId?: string
    reviewStatus?: number
    reviewMessage?: string
    reviewerId?: string
  }

  type PictureReviewRequest = {
    id?: string
    reviewStatus?: number
    reviewMessage?: string
  }

  type PictureTagCategory = {
    tagList?: string[]
    categoryList?: string[]
  }

  type PictureUpdateRequest = {
    id?: string
    name?: string
    introduction?: string
    category?: string
    tags?: string[]
  }

  type PictureUploadRequest = {
    id?: string
  }

  // 搜索联想：名称（可点击进详情）、标签、分类三组前缀匹配结果
  type SearchNameSuggestion = {
    id?: string
    name?: string
  }

  type SearchSuggestVO = {
    names?: SearchNameSuggestion[]
    tags?: string[]
    categories?: string[]
  }

  // 热门搜索词：标签 / 分类 + 公开图片数量
  type SearchHotKeyword = {
    name?: string
    count?: number
  }

  type SearchHotVO = {
    tags?: SearchHotKeyword[]
    categories?: SearchHotKeyword[]
  }

  type PictureVO = {
    id?: string
    url?: string
    name?: string
    introduction?: string
    tags?: string[]
    category?: string
    picSize?: string
    downloadCount?: string
    // 收藏数 / 点赞数：冗余计数，列表展示与热度排序用
    favoriteCount?: string
    likeCount?: string
    picWidth?: number
    picHeight?: number
    picScale?: number
    picFormat?: string
    userId?: string
    createTime?: string
    editTime?: string
    updateTime?: string
    reviewStatus?: number
    // 审核意见 / 审核人 / 审核时间仅在详情和「我的图片」中、且请求者是作者或管理员时返回
    reviewMessage?: string
    reviewerId?: string
    reviewTime?: string
    // 是否命中同用户秒传：true 表示返回的是已存在的图片
    duplicated?: boolean
    user?: PublicUserVO
  }

  type testDownloadFileParams = {
    filepath: string
  }

  type uploadPictureParams = {
    pictureUploadRequest: PictureUploadRequest
  }

  type User = {
    id?: string
    userAccount?: string
    userPassword?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    editTime?: string
    createTime?: string
    updateTime?: string
    isDelete?: number
  }

  type UserAddRequest = {
    userName?: string
    userAccount?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
  }

  type UserAddResult = {
    id?: string
    initialPassword?: string
  }

  type UserLoginRequest = {
    userAccount?: string
    userPassword?: string
  }

  type UserQueryRequest = {
    current?: number
    pageSize?: number
    sortField?: string
    sortOrder?: string
    id?: string
    userName?: string
    userAccount?: string
    userProfile?: string
    userRole?: string
  }

  type UserRegisterRequest = {
    userAccount?: string
    userPassword?: string
    checkPassword?: string
    captchaId?: string
    captchaCode?: string
  }

  type UserUpdateRequest = {
    id?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
  }

  type UserVO = {
    id?: string
    userAccount?: string
    userName?: string
    userAvatar?: string
    userProfile?: string
    userRole?: string
    // 配额：已用/上限，管理员在用户列表里查看与调整
    storageQuota?: string
    storageUsed?: string
    pictureQuota?: number
    pictureUsed?: number
    createTime?: string
  }
}
