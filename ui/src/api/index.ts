import { axiosInstance } from '@halo-dev/api-client'
import { ClawBotV1alpha1ConsoleApi } from './generated'

export const clawBotApi = new ClawBotV1alpha1ConsoleApi(undefined, '', axiosInstance)
